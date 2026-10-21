package com.revenugenereate.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SqliteMigrationRunnerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesFreshDatabaseAndCanMigrateRepeatedlyWithoutDeletingData() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("fresh.db"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        SqliteMigrationRunner migrationRunner = new SqliteMigrationRunner();

        migrationRunner.migrate(jdbcTemplate);
        jdbcTemplate.update(
                "INSERT INTO scan_runs(id, status, market_code, niche_code) VALUES (?, ?, ?, ?)",
                "existing-scan",
                "DEMO",
                "US",
                "AI_DEV_TOOLS");
        migrationRunner.migrate(jdbcTemplate);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migrations", Integer.class)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scan_runs WHERE id = 'existing-scan'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name IN "
                        + "('scan_runs','source_evidence','opportunities','content_drafts','offers',"
                        + "'review_decisions','publication_records','source_profiles',"
                        + "'discovery_scans','discovery_results','google_trends_imports','youtube_quota_usage',"
                        + "'watchlist_sources','watchlist_refresh_history','research_notes')",
                Integer.class)).isEqualTo(15);
    }
}
