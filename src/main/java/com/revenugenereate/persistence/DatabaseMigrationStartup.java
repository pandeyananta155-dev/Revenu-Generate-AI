package com.revenugenereate.persistence;

import com.revenugenereate.catalog.ProviderPolicyGuard;
import com.revenugenereate.config.AppProperties;
import com.revenugenereate.watchlist.WatchlistService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.SmartInitializingSingleton;

@Configuration
public class DatabaseMigrationStartup {

    @Bean
    SmartInitializingSingleton initializeLocalDatabase(
            JdbcTemplate jdbcTemplate,
            AppProperties properties,
            CatalogRepository repository,
            ProviderPolicyGuard policyGuard,
            WatchlistService watchlistService) {
        return () -> {
            properties.catalogs().providers().forEach(policyGuard::validate);
            new SqliteMigrationRunner().migrate(jdbcTemplate);
            repository.seed(properties.catalogs());
            watchlistService.seedConfiguredSources();
            watchlistService.pruneExpiredHistory();
        };
    }
}
