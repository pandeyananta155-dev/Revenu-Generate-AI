package com.revenugenereate.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.revenugenereate.config.AppProperties;
import com.revenugenereate.config.CatalogProperties;
import com.revenugenereate.discovery.DiscoveryModels.ScanRequest;
import com.revenugenereate.discovery.DiscoveryProvider.ProviderContext;
import com.revenugenereate.discovery.DiscoveryProvider.ProviderItem;
import com.revenugenereate.persistence.CatalogRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryServiceTest {
    @Test
    void isolatesProviderFailuresAndPersistsSuccessfulEvidence() throws Exception {
        CatalogRepository catalogRepository = mock(CatalogRepository.class);
        DiscoveryRepository repository = mock(DiscoveryRepository.class);
        when(catalogRepository.findAllProviders()).thenReturn(List.of());
        DiscoveryProvider failing = provider("FAIL_PROVIDER", context -> {
            throw new IllegalStateException("provider failure");
        });
        DiscoveryProvider successful = provider("GOOD_PROVIDER", context -> List.of(
                new ProviderItem("record", "A useful trend", null, "https://publisher.example/story",
                        "Publisher", Instant.parse("2026-10-20T10:00:00Z"), "en", null, null, null,
                        "Test adapter", List.of("Attribution preserved."))));
        DiscoveryService service = new DiscoveryService(appProperties(), catalogRepository,
                properties(), repository, List.of(failing, successful));

        var scan = service.runScan(new ScanRequest("US", "TECH_GENERAL", 7,
                List.of("FAIL_PROVIDER", "GOOD_PROVIDER"), 5));

        assertThat(scan.status()).isEqualTo("PARTIAL");
        assertThat(scan.resultCount()).isEqualTo(1);
        assertThat(scan.providers()).extracting("status").containsExactly("FAILED", "COMPLETED");
        verify(repository).save(org.mockito.ArgumentMatchers.eq(scan),
                argThat(items -> items.size() == 1 && items.getFirst().title().equals("A useful trend")));
    }

    @Test
    void marksEmptyScansAndFiltersTimestampedEvidenceOutsideRequestedWindow() throws Exception {
        CatalogRepository catalogRepository = mock(CatalogRepository.class);
        DiscoveryRepository repository = mock(DiscoveryRepository.class);
        when(catalogRepository.findAllProviders()).thenReturn(List.of());
        DiscoveryProvider provider = provider("WINDOW_PROVIDER", context -> List.of(
                new ProviderItem("old", "Outside window", null, "https://publisher.example/old",
                        "Publisher", context.since().minusSeconds(1), null, null, null, null,
                        "Test adapter", List.of()),
                new ProviderItem("undated", "Date unavailable", null, "https://publisher.example/undated",
                        "Publisher", null, null, null, null, null, "Test adapter", List.of())));
        DiscoveryService service = new DiscoveryService(appProperties(), catalogRepository,
                properties(), repository, List.of(provider));

        var scan = service.runScan(new ScanRequest("US", "TECH_GENERAL", 7,
                List.of("WINDOW_PROVIDER"), 5));

        assertThat(scan.status()).isEqualTo("COMPLETED");
        assertThat(scan.resultCount()).isEqualTo(1);
        verify(repository).save(org.mockito.ArgumentMatchers.eq(scan),
                argThat(items -> items.size() == 1 && items.getFirst().publishedAt() == null));

        DiscoveryProvider emptyProvider = provider("EMPTY_PROVIDER", context -> List.of());
        DiscoveryService emptyService = new DiscoveryService(appProperties(), catalogRepository,
                properties(), repository, List.of(emptyProvider));
        var emptyScan = emptyService.runScan(new ScanRequest("US", "TECH_GENERAL", 7,
                List.of("EMPTY_PROVIDER"), 5));
        assertThat(emptyScan.status()).isEqualTo("NO_RESULTS");
    }

    private static DiscoveryProvider provider(String code, DiscoveryFunction function) {
        return new DiscoveryProvider() {
            @Override public String code() { return code; }
            @Override public DiscoveryModels.ProviderStatus status() {
                return new DiscoveryModels.ProviderStatus(code, code, "AVAILABLE", "Mock provider.");
            }
            @Override public List<ProviderItem> discover(ProviderContext context) throws Exception {
                return function.discover(context);
            }
        };
    }

    @FunctionalInterface
    private interface DiscoveryFunction {
        List<ProviderItem> discover(ProviderContext context) throws Exception;
    }

    private static AppProperties appProperties() {
        CatalogProperties catalogs = new CatalogProperties(
                List.of(new CatalogProperties.Market("US", "United States", "US", "en", "America/New_York")),
                List.of(new CatalogProperties.Niche("TECH_GENERAL", "General technology", "Technology topics")),
                List.of(), List.of(), new CatalogProperties.Watchlist(List.of()));
        return new AppProperties("Test", "0.1", "target", catalogs);
    }

    private static DiscoveryProperties properties() {
        return new DiscoveryProperties(20, 1048576, 1048576, Duration.ofSeconds(1), Duration.ZERO,
                List.of("technology"), Map.of("TECH_GENERAL", List.of("software trends")),
                new DiscoveryProperties.Gdelt(false, "https://api.gdeltproject.org/api/v2/doc/doc", Duration.ZERO),
                new DiscoveryProperties.Youtube(false, "YOUTUBE_API_KEY", 2, 10000, 30),
                new DiscoveryProperties.Rss(false));
    }
}
