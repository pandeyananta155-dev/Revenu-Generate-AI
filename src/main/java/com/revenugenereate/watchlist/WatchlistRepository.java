package com.revenugenereate.watchlist;

import com.revenugenereate.watchlist.WatchlistModels.RefreshHistory;
import com.revenugenereate.watchlist.WatchlistModels.ResearchNote;
import com.revenugenereate.watchlist.WatchlistModels.Source;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Persists Phase 4 watchlist configuration, bounded YouTube refresh history, and research notes in SQLite. */
@Repository
public class WatchlistRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public WatchlistRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Inserts a new watchlist configuration without triggering provider access. */
    public void insert(Source source) {
        jdbc.sql("""
                INSERT INTO watchlist_sources
                (id, source_type, source_url, external_source_id, display_name, country_code, niche_code,
                 keywords_json, priority, enabled, refresh_interval_hours, source_status,
                 created_at, updated_at)
                VALUES (:id, :type, :url, :externalId, :name, :country, :niche, :keywords, :priority,
                 :enabled, :interval, :status, :created, :updated)
                """)
                .param("id", source.id()).param("type", source.sourceType()).param("url", source.sourceUrl())
                .param("externalId", source.externalSourceId()).param("name", source.displayName())
                .param("country", source.countryCode()).param("niche", source.nicheCode())
                .param("keywords", json(source.keywords())).param("priority", source.priority())
                .param("enabled", source.enabled() ? 1 : 0).param("interval", source.refreshIntervalHours())
                .param("status", source.sourceStatus()).param("created", text(source.createdAt()))
                .param("updated", text(source.updatedAt())).update();
    }

    /** Checks the uniqueness dimensions before insert/update; the database index remains the concurrency guard. */
    public boolean existsDuplicate(String id, String sourceType, String sourceUrl, String countryCode, String nicheCode) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM watchlist_sources WHERE source_type=:type AND source_url=:url
                  AND IFNULL(country_code, '')=IFNULL(:country, '')
                  AND IFNULL(niche_code, '')=IFNULL(:niche, '')
                  AND (:id IS NULL OR id<>:id)
                """)
                .param("type", sourceType).param("url", sourceUrl).param("country", countryCode)
                .param("niche", nicheCode).param("id", id).query(Long.class).single() > 0;
    }

    /** Reads a source by stable ID, including archived sources. */
    public Optional<Source> findSource(String id) {
        return jdbc.sql("SELECT * FROM watchlist_sources WHERE id = :id").param("id", id)
                .query((rs, row) -> new Source(rs.getString("id"), rs.getString("source_type"),
                        rs.getString("source_url"), rs.getString("external_source_id"), rs.getString("display_name"),
                        rs.getString("country_code"), rs.getString("niche_code"),
                        readList(rs.getString("keywords_json"), String[].class), rs.getInt("priority"),
                        rs.getInt("enabled") == 1, rs.getInt("refresh_interval_hours"), rs.getString("source_status"),
                        instant(rs.getString("last_checked_at")), instant(rs.getString("last_successful_check_at")),
                        rs.getString("last_error"), instant(rs.getString("created_at")),
                        instant(rs.getString("updated_at")), instant(rs.getString("archived_at")))).optional();
    }

    /** Lists active sources, optionally including archived entries. */
    public List<Source> findSources(boolean includeArchived, int limit, int offset) {
        return jdbc.sql("SELECT id FROM watchlist_sources WHERE (:all = 1 OR archived_at IS NULL) ORDER BY created_at, id LIMIT :limit OFFSET :offset")
                .param("all", includeArchived ? 1 : 0).param("limit", limit).param("offset", offset)
                .query(String.class).list().stream()
                .map(id -> findSource(id).orElseThrow()).toList();
    }

    /** Counts active or all sources for the paginated watchlist response. */
    public long countSources(boolean includeArchived) {
        return jdbc.sql("SELECT COUNT(*) FROM watchlist_sources WHERE (:all = 1 OR archived_at IS NULL)")
                .param("all", includeArchived ? 1 : 0).query(Long.class).single();
    }

    /** Updates editable source fields while preserving its identity and history. */
    public void update(Source source) {
        jdbc.sql("""
                UPDATE watchlist_sources SET source_type=:type, source_url=:url, external_source_id=:externalId,
                  display_name=:name, country_code=:country, niche_code=:niche, keywords_json=:keywords,
                  priority=:priority, enabled=:enabled, refresh_interval_hours=:interval,
                  source_status=:status, last_error=:error, updated_at=:updated
                WHERE id=:id
                """)
                .param("type", source.sourceType()).param("url", source.sourceUrl())
                .param("externalId", source.externalSourceId()).param("name", source.displayName())
                .param("country", source.countryCode()).param("niche", source.nicheCode())
                .param("keywords", json(source.keywords())).param("priority", source.priority())
                .param("enabled", source.enabled() ? 1 : 0).param("interval", source.refreshIntervalHours())
                .param("status", source.sourceStatus()).param("error", source.lastError())
                .param("updated", text(source.updatedAt())
                ).param("id", source.id()).update();
    }

    /** Disables and archives a source without removing its refresh history or linked notes. */
    public void archive(String id, Instant now) {
        jdbc.sql("UPDATE watchlist_sources SET enabled=0, source_status='ARCHIVED', archived_at=:now, updated_at=:now WHERE id=:id")
                .param("now", text(now)).param("id", id).update();
    }

    /** Records refresh outcome and capped provider data, then prunes expired and excess history. */
    public void saveHistory(RefreshHistory history) {
        jdbc.sql("""
                INSERT INTO watchlist_refresh_history
                (id, source_id, checked_at, outcome, records_processed, message,
                 request_references_json, records_json, delete_after)
                VALUES (:id, :source, :checked, :outcome, :count, :message, :references, :records, :deleteAfter)
                """)
                .param("id", history.id()).param("source", history.sourceId()).param("checked", text(history.checkedAt()))
                .param("outcome", history.outcome()).param("count", history.recordsProcessed())
                .param("message", history.message()).param("references", json(history.requestReferences()))
                .param("records", json(history.records())).param("deleteAfter", text(history.deleteAfter())).update();
        jdbc.sql("DELETE FROM watchlist_refresh_history WHERE delete_after <= :now")
                .param("now", text(Instant.now())).update();
        jdbc.sql("""
                DELETE FROM watchlist_refresh_history WHERE id IN (
                  SELECT id FROM watchlist_refresh_history WHERE source_id=:source
                  ORDER BY checked_at DESC LIMIT -1 OFFSET 100)
                """).param("source", history.sourceId()).update();
    }

    /** Returns recent, still-retained refresh attempts for a source. */
    public List<RefreshHistory> findHistory(String sourceId, int limit, int offset) {
        pruneExpiredHistory();
        return jdbc.sql("""
                SELECT * FROM watchlist_refresh_history WHERE source_id=:source
                ORDER BY checked_at DESC, id LIMIT :limit OFFSET :offset
                """).param("source", sourceId).param("limit", limit).param("offset", offset)
                .query((rs, row) -> new RefreshHistory(rs.getString("id"), rs.getString("source_id"),
                        instant(rs.getString("checked_at")), rs.getString("outcome"), rs.getInt("records_processed"),
                        rs.getString("message"), readList(rs.getString("request_references_json"), String[].class),
                        readList(rs.getString("records_json"), WatchlistModels.RefreshItem[].class),
                        instant(rs.getString("delete_after")))).list();
    }

    /** Updates check state and optionally stores a resolved stable channel ID. */
    public void recordCheck(Source source, Instant checkedAt, boolean succeeded, String error, String resolvedId) {
        jdbc.sql("""
                UPDATE watchlist_sources SET source_status=:status, last_checked_at=:checked,
                  last_successful_check_at=CASE WHEN :success=1 THEN :checked ELSE last_successful_check_at END,
                  last_error=:error, external_source_id=COALESCE(:resolvedId, external_source_id), updated_at=:checked
                WHERE id=:id
                """)
                .param("status", succeeded ? "ACTIVE" : "ERROR").param("checked", text(checkedAt))
                .param("success", succeeded ? 1 : 0).param("error", error).param("resolvedId", resolvedId)
                .param("id", source.id()).update();
    }

    /** Inserts a personal note linked to an optional existing source. */
    public void insertNote(ResearchNote note) {
        jdbc.sql("""
                INSERT INTO research_notes
                (id, source_id, title, note_text, related_source_url, country_code, niche_code, tags_json, created_at, updated_at)
                VALUES (:id, :source, :title, :body, :url, :country, :niche, :tags, :created, :updated)
                """)
                .param("id", note.id()).param("source", note.sourceId()).param("title", note.title())
                .param("body", note.noteText()).param("url", note.relatedSourceUrl()).param("country", note.countryCode())
                .param("niche", note.nicheCode()).param("tags", json(note.tags()))
                .param("created", text(note.createdAt())).param("updated", text(note.updatedAt())).update();
    }

    /** Returns one research note. */
    public Optional<ResearchNote> findNote(String id) {
        return jdbc.sql("SELECT * FROM research_notes WHERE id=:id").param("id", id)
                .query((rs, row) -> readNote(rs)).optional();
    }

    /** Lists notes, optionally filtered by their linked source. */
    public List<ResearchNote> findNotes(String sourceId, int limit, int offset) {
        return jdbc.sql("""
                SELECT * FROM research_notes WHERE (:source IS NULL OR source_id=:source)
                ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset
                """).param("source", sourceId).param("limit", limit).param("offset", offset)
                .query((rs, row) -> readNote(rs)).list();
    }

    /** Counts notes matching the optional source filter. */
    public long countNotes(String sourceId) {
        return jdbc.sql("SELECT COUNT(*) FROM research_notes WHERE (:source IS NULL OR source_id=:source)")
                .param("source", sourceId).query(Long.class).single();
    }

    /** Updates an existing personal note. */
    public void updateNote(ResearchNote note) {
        jdbc.sql("""
                UPDATE research_notes SET source_id=:source, title=:title, note_text=:body,
                  related_source_url=:url, country_code=:country, niche_code=:niche, tags_json=:tags, updated_at=:updated
                WHERE id=:id
                """)
                .param("source", note.sourceId()).param("title", note.title()).param("body", note.noteText())
                .param("url", note.relatedSourceUrl()).param("country", note.countryCode()).param("niche", note.nicheCode())
                .param("tags", json(note.tags())).param("updated", text(note.updatedAt())).param("id", note.id()).update();
    }

    /** Deletes one note without changing its watched source or refresh history. */
    public void deleteNote(String id) {
        jdbc.sql("DELETE FROM research_notes WHERE id=:id").param("id", id).update();
    }

    /** Removes refresh data at its YouTube retention deadline. */
    public void pruneExpiredHistory() {
        jdbc.sql("DELETE FROM watchlist_refresh_history WHERE delete_after <= :now")
                .param("now", text(Instant.now())).update();
    }

    private ResearchNote readNote(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ResearchNote(rs.getString("id"), rs.getString("source_id"), rs.getString("title"),
                rs.getString("note_text"), rs.getString("related_source_url"), rs.getString("country_code"),
                rs.getString("niche_code"), readList(rs.getString("tags_json"), String[].class),
                instant(rs.getString("created_at")), instant(rs.getString("updated_at")));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not serialize watchlist data", exception); }
    }

    private <T> List<T> readList(String value, Class<T[]> type) {
        try { return List.of(mapper.readValue(value, type)); }
        catch (JacksonException exception) { throw new IllegalStateException("Could not read watchlist data", exception); }
    }

    private static String text(Instant instant) { return instant == null ? null : instant.toString(); }
    private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }
}
