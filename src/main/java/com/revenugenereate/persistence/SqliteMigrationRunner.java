package com.revenugenereate.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public class SqliteMigrationRunner {

    private static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS scan_runs (
                        id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        market_code TEXT NOT NULL,
                        niche_code TEXT NOT NULL,
                        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        completed_at TEXT,
                        result_json TEXT NOT NULL DEFAULT '{}'
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS source_evidence (
                        id TEXT PRIMARY KEY,
                        scan_run_id TEXT NOT NULL,
                        provider_code TEXT NOT NULL,
                        source_url TEXT NOT NULL,
                        observed_at TEXT,
                        fetched_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        market_code TEXT NOT NULL,
                        niche_code TEXT NOT NULL,
                        retained_fields_json TEXT NOT NULL DEFAULT '{}',
                        permissions_json TEXT NOT NULL DEFAULT '{}',
                        terms_review_state TEXT NOT NULL,
                        expires_at TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (scan_run_id) REFERENCES scan_runs(id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS opportunities (
                        id TEXT PRIMARY KEY,
                        market_code TEXT NOT NULL,
                        niche_code TEXT NOT NULL,
                        evidence_ids_json TEXT NOT NULL DEFAULT '[]',
                        score_json TEXT NOT NULL DEFAULT '{}',
                        status TEXT NOT NULL DEFAULT 'DRAFT',
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at TEXT
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS content_drafts (
                        id TEXT PRIMARY KEY,
                        opportunity_id TEXT,
                        status TEXT NOT NULL DEFAULT 'DRAFT',
                        brief_json TEXT NOT NULL DEFAULT '{}',
                        script_json TEXT NOT NULL DEFAULT '{}',
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at TEXT,
                        FOREIGN KEY (opportunity_id) REFERENCES opportunities(id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS offers (
                        code TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        destination_url TEXT NOT NULL,
                        supported_countries_json TEXT NOT NULL,
                        approved_claims_json TEXT NOT NULL,
                        terms_review_date TEXT NOT NULL,
                        disclosure TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS review_decisions (
                        id TEXT PRIMARY KEY,
                        content_draft_id TEXT NOT NULL,
                        reviewer TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        notes TEXT NOT NULL DEFAULT '',
                        decided_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at TEXT,
                        FOREIGN KEY (content_draft_id) REFERENCES content_drafts(id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS publication_records (
                        id TEXT PRIMARY KEY,
                        content_draft_id TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        external_id TEXT,
                        status TEXT NOT NULL,
                        scheduled_at TEXT,
                        published_at TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (content_draft_id) REFERENCES content_drafts(id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS source_profiles (
                        code TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        source_type TEXT NOT NULL,
                        endpoint_urls_json TEXT NOT NULL DEFAULT '[]',
                        enabled INTEGER NOT NULL DEFAULT 0,
                        permissions_json TEXT NOT NULL DEFAULT '{}',
                        terms_url TEXT NOT NULL,
                        terms_review_date TEXT NOT NULL,
                        retention_days INTEGER,
                        required_approvals_json TEXT NOT NULL DEFAULT '[]',
                        secret_environment_variables_json TEXT NOT NULL DEFAULT '[]',
                        reddit_permission_reference TEXT,
                        youtube_approval_reference TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_evidence_scan_run ON source_evidence(scan_run_id)",
                    "CREATE INDEX IF NOT EXISTS idx_opportunities_market_niche ON opportunities(market_code, niche_code)"),
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS discovery_scans (
                        id TEXT PRIMARY KEY,
                        market_code TEXT NOT NULL,
                        country_code TEXT NOT NULL,
                        niche_code TEXT NOT NULL,
                        window_days INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        started_at TEXT NOT NULL,
                        completed_at TEXT NOT NULL,
                        provider_summaries_json TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS discovery_results (
                        id TEXT PRIMARY KEY,
                        scan_id TEXT NOT NULL,
                        provider_code TEXT NOT NULL,
                        provider_record_id TEXT,
                        country_code TEXT NOT NULL,
                        country_semantics TEXT NOT NULL,
                        niche_code TEXT NOT NULL,
                        query_terms_json TEXT NOT NULL DEFAULT '[]',
                        title TEXT NOT NULL,
                        excerpt TEXT,
                        source_url TEXT NOT NULL,
                        source_name TEXT,
                        published_at TEXT,
                        fetched_at TEXT NOT NULL,
                        language TEXT,
                        youtube_metrics_json TEXT,
                        google_trends_native_interest TEXT,
                        native_rank INTEGER,
                        provenance TEXT NOT NULL,
                        limitations_json TEXT NOT NULL DEFAULT '[]',
                        processing_status TEXT NOT NULL,
                        delete_after TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (scan_id) REFERENCES discovery_scans(id)
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS youtube_quota_usage (
                        usage_date TEXT PRIMARY KEY,
                        quota_units INTEGER NOT NULL DEFAULT 0
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS google_trends_imports (
                        id TEXT PRIMARY KEY,
                        filename TEXT NOT NULL,
                        country_code TEXT,
                        niche_code TEXT,
                        time_window TEXT,
                        row_count INTEGER NOT NULL,
                        source_label TEXT NOT NULL,
                        imported_at TEXT NOT NULL,
                        attribution TEXT NOT NULL
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_discovery_results_scan ON discovery_results(scan_id)",
                    "CREATE INDEX IF NOT EXISTS idx_discovery_results_filter ON discovery_results(country_code, niche_code, provider_code, fetched_at)"),
            List.of(
                    """
                    CREATE TABLE IF NOT EXISTS watchlist_sources (
                        id TEXT PRIMARY KEY,
                        source_type TEXT NOT NULL CHECK (source_type IN ('YOUTUBE_CHANNEL', 'YOUTUBE_VIDEO')),
                        source_url TEXT NOT NULL,
                        external_source_id TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        country_code TEXT,
                        niche_code TEXT,
                        keywords_json TEXT NOT NULL DEFAULT '[]',
                        priority INTEGER NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
                        enabled INTEGER NOT NULL DEFAULT 1,
                        refresh_interval_hours INTEGER NOT NULL DEFAULT 24 CHECK (refresh_interval_hours BETWEEN 1 AND 720),
                        source_status TEXT NOT NULL DEFAULT 'PENDING',
                        last_checked_at TEXT,
                        last_successful_check_at TEXT,
                        last_error TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        archived_at TEXT
                    )
                    """,
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_watchlist_sources_unique ON watchlist_sources(source_type, source_url, IFNULL(country_code, ''), IFNULL(niche_code, ''))",
                    "CREATE INDEX IF NOT EXISTS idx_watchlist_sources_enabled ON watchlist_sources(enabled, archived_at)",
                    """
                    CREATE TABLE IF NOT EXISTS watchlist_refresh_history (
                        id TEXT PRIMARY KEY,
                        source_id TEXT NOT NULL,
                        checked_at TEXT NOT NULL,
                        outcome TEXT NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
                        records_processed INTEGER NOT NULL DEFAULT 0,
                        message TEXT NOT NULL,
                        request_references_json TEXT NOT NULL DEFAULT '[]',
                        records_json TEXT NOT NULL DEFAULT '[]',
                        delete_after TEXT NOT NULL,
                        FOREIGN KEY (source_id) REFERENCES watchlist_sources(id)
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_watchlist_history_source ON watchlist_refresh_history(source_id, checked_at DESC)",
                    """
                    CREATE TABLE IF NOT EXISTS research_notes (
                        id TEXT PRIMARY KEY,
                        source_id TEXT,
                        title TEXT NOT NULL,
                        note_text TEXT NOT NULL,
                        related_source_url TEXT,
                        country_code TEXT,
                        niche_code TEXT,
                        tags_json TEXT NOT NULL DEFAULT '[]',
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        FOREIGN KEY (source_id) REFERENCES watchlist_sources(id)
                    )
                    """,
                    "CREATE INDEX IF NOT EXISTS idx_research_notes_source ON research_notes(source_id, created_at DESC)"));

    public void migrate(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schema_migrations (
                    version INTEGER PRIMARY KEY,
                    applied_at TEXT NOT NULL
                )
                """);
        for (int index = 0; index < MIGRATIONS.size(); index++) {
            int version = index + 1;
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schema_migrations WHERE version = ?", Integer.class, version);
            if (count != null && count > 0) {
                continue;
            }
            for (String statement : MIGRATIONS.get(index)) {
                jdbcTemplate.execute(statement);
            }
            jdbcTemplate.update(
                    "INSERT INTO schema_migrations(version, applied_at) VALUES (?, ?)",
                    version,
                    Instant.now().toString());
        }
    }
}
