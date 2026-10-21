package com.revenugenereate.discovery;

import com.revenugenereate.discovery.DiscoveryModels.ProviderSummary;
import com.revenugenereate.discovery.DiscoveryModels.Result;
import com.revenugenereate.discovery.DiscoveryModels.Scan;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Stores discovery scans, provider evidence, and CSV import audit metadata in SQLite. */
@Repository
public class DiscoveryRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public DiscoveryRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** Saves a completed scan and all successful provider items in one transaction-managed call. */
    public void save(Scan scan, List<Result> results) {
        jdbc.sql("""
                INSERT INTO discovery_scans
                (id, market_code, country_code, niche_code, window_days, status, started_at, completed_at, provider_summaries_json)
                VALUES (:id, :market, :country, :niche, :days, :status, :started, :completed, :summaries)
                """)
                .param("id", scan.id()).param("market", scan.marketCode())
                .param("country", scan.countryCode()).param("niche", scan.nicheCode())
                .param("days", scan.windowDays()).param("status", scan.status())
                .param("started", scan.startedAt().toString()).param("completed", scan.completedAt().toString())
                .param("summaries", json(scan.providers())).update();
        for (Result result : results) {
            jdbc.sql("""
                    INSERT INTO discovery_results
                    (id, scan_id, provider_code, provider_record_id, country_code, country_semantics,
                     niche_code, query_terms_json, title, excerpt, source_url, source_name, published_at,
                     fetched_at, language, youtube_metrics_json, google_trends_native_interest, native_rank, provenance,
                     limitations_json, processing_status, delete_after)
                    VALUES (:id, :scan, :provider, :record, :country, :semantics, :niche, :terms,
                     :title, :excerpt, :url, :source, :published, :fetched, :language, :metrics, :interest,
                     :rank, :provenance, :limitations, :processing, :deleteAfter)
                    """)
                    .param("id", result.id()).param("scan", scan.id()).param("provider", result.providerCode())
                    .param("record", result.providerRecordId()).param("country", result.countryCode())
                    .param("semantics", result.countrySemantics()).param("niche", result.nicheCode())
                    .param("terms", json(result.queryTerms())).param("title", result.title())
                    .param("excerpt", result.excerpt()).param("url", result.sourceUrl())
                    .param("source", result.sourceName()).param("published", text(result.publishedAt()))
                    .param("fetched", result.fetchedAt().toString()).param("language", result.language())
                    .param("metrics", result.youtubeNativeMetrics() == null ? null : json(result.youtubeNativeMetrics()))
                    .param("interest", result.googleTrendsNativeInterest())
                    .param("rank", result.nativeRank()).param("provenance", result.provenance())
                    .param("limitations", json(result.limitations())).param("processing", result.processingStatus())
                    .param("deleteAfter", text(result.deleteAfter())).update();
        }
    }

    /** Loads a single scan summary and its result count. */
    public Optional<Scan> findScan(String id) {
        return jdbc.sql("""
                SELECT s.id, s.market_code, s.country_code, s.niche_code, s.window_days, s.status, s.started_at,
                       s.completed_at, s.provider_summaries_json,
                       (SELECT COUNT(*) FROM discovery_results r WHERE r.scan_id = s.id) AS result_count
                FROM discovery_scans s
                WHERE s.id = :id
                """).param("id", id).query((rs, row) -> new Scan(
                rs.getString("id"), rs.getString("market_code"), rs.getString("country_code"),
                rs.getString("niche_code"), rs.getInt("window_days"), rs.getString("status"),
                Instant.parse(rs.getString("started_at")), Instant.parse(rs.getString("completed_at")),
                readList(rs.getString("provider_summaries_json"), ProviderSummary[].class),
                rs.getInt("result_count"))).optional();
    }

    /** Loads a scan's retained evidence with provider and market filters plus stable pagination. */
    public List<Result> findResults(
            String scanId, String provider, String country, String niche, int limit, long offset) {
        return jdbc.sql("""
                SELECT * FROM discovery_results
                WHERE scan_id = :scan
                  AND (:provider IS NULL OR provider_code = :provider)
                  AND (:country IS NULL OR country_code = :country)
                  AND (:niche IS NULL OR niche_code = :niche)
                ORDER BY fetched_at DESC, id
                LIMIT :limit OFFSET :offset
                """).param("scan", scanId).param("provider", provider).param("country", country)
                .param("niche", niche).param("limit", limit).param("offset", offset)
                .query((rs, row) -> new Result(
                        rs.getString("id"), rs.getString("provider_code"), rs.getString("provider_record_id"),
                        rs.getString("country_code"), rs.getString("country_semantics"), rs.getString("niche_code"),
                        readList(rs.getString("query_terms_json"), String[].class), rs.getString("title"),
                        rs.getString("excerpt"), rs.getString("source_url"), rs.getString("source_name"),
                        instant(rs.getString("published_at")), Instant.parse(rs.getString("fetched_at")),
                        rs.getString("language"), readMap(rs.getString("youtube_metrics_json")),
                        rs.getString("google_trends_native_interest"),
                        (Integer) rs.getObject("native_rank"), rs.getString("provenance"),
                        readList(rs.getString("limitations_json"), String[].class), rs.getString("processing_status"),
                        instant(rs.getString("delete_after")))).list();
    }

    /** Counts results matching the supplied result filters. */
    public long countResults(String scanId, String provider, String country, String niche) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM discovery_results
                WHERE scan_id = :scan
                  AND (:provider IS NULL OR provider_code = :provider)
                  AND (:country IS NULL OR country_code = :country)
                  AND (:niche IS NULL OR niche_code = :niche)
                """).param("scan", scanId).param("provider", provider).param("country", country)
                .param("niche", niche).query(Long.class).single();
    }

    /** Removes evidence whose configured source-retention deadline has elapsed. */
    public void deleteExpiredResults(Instant now) {
        jdbc.sql("DELETE FROM discovery_results WHERE delete_after IS NOT NULL AND delete_after <= :now")
                .param("now", now.toString()).update();
    }

    /** Persists the audit record for a user-provided Google Trends export. */
    public void saveImport(DiscoveryModels.TrendsImportResponse response) {
        jdbc.sql("""
                INSERT INTO google_trends_imports
                (id, filename, country_code, niche_code, time_window, row_count, source_label, imported_at, attribution)
                VALUES (:id, :filename, :country, :niche, :window, :count, :source, :imported, :attribution)
                """).param("id", response.id()).param("filename", response.filename())
                .param("country", response.countryCode()).param("niche", response.nicheCode())
                .param("window", response.timeWindow()).param("count", response.rowCount())
                .param("source", response.sourceLabel()).param("imported", response.importedAt().toString())
                .param("attribution", response.attribution()).update();
    }

    /** Atomically consumes a bounded number of locally tracked API calls for the current UTC day. */
    public boolean reserveYoutubeQuota(int quotaUnits, int dailyBudget) {
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        int changed = jdbc.sql("""
                INSERT INTO youtube_quota_usage (usage_date, quota_units) VALUES (:day, :units)
                ON CONFLICT(usage_date) DO UPDATE SET quota_units = quota_units + :units
                WHERE quota_units + :units <= :budget
                """).param("day", today).param("units", quotaUnits).param("budget", dailyBudget).update();
        return changed == 1;
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize discovery evidence", exception);
        }
    }

    private <T> List<T> readList(String json, Class<T[]> type) {
        try {
            return List.of(mapper.readValue(json, type));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not read persisted discovery evidence", exception);
        }
    }

    private java.util.Map<String, Long> readMap(String json) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, mapper.getTypeFactory()
                    .constructMapType(java.util.Map.class, String.class, Long.class));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not read persisted provider metrics", exception);
        }
    }

    private static String text(Instant value) { return value == null ? null : value.toString(); }
    private static Instant instant(String value) { return value == null ? null : Instant.parse(value); }
}
