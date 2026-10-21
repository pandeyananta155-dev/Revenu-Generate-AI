package com.revenugenereate.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.revenugenereate.api.ApiRequestException;
import com.revenugenereate.config.CatalogProperties;
import com.revenugenereate.discovery.DiscoveryModels.Scan;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class GoogleTrendsCsvServiceTest {
    @Test
    void importsValuesAsAttributedTextAndNeverEvaluatesFormulas() throws Exception {
        DiscoveryService discovery = mock(DiscoveryService.class);
        DiscoveryRepository repository = mock(DiscoveryRepository.class);
        Instant now = Instant.now();
        when(discovery.saveImportedResults(eq("US"), eq("US"), eq("AI_DEV_TOOLS"), any(), any()))
                .thenAnswer(invocation -> {
                    List<DiscoveryProvider.ProviderItem> items = invocation.getArgument(4);
                    assertThat(items).hasSize(2);
                    assertThat(items.get(0).googleTrendsNativeInterest()).isEqualTo("100");
                    assertThat(items.get(0).title()).isEqualTo("AI coding assistant");
                    assertThat(items.get(0).limitations()).anyMatch(text -> text.contains("not absolute search volume"));
                    assertThat(items.get(1).googleTrendsNativeInterest()).isEqualTo("<1");
                    return new Scan("scan-id", "US", "US", "AI_DEV_TOOLS", 1,
                            "COMPLETED", now, now, List.of(), items.size());
                });
        GoogleTrendsCsvService service = new GoogleTrendsCsvService(
                properties(), discovery, repository);
        MockMultipartFile file = new MockMultipartFile("file", "../trends.csv", "text/csv",
                "Query,Interest,Date\nAI coding assistant,100,2026-10-01\nDeveloper tools,<1,2026-10-02\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var result = service.importCsv(file, "US", "AI_DEV_TOOLS", markets());

        assertThat(result.scanId()).isEqualTo("scan-id");
        assertThat(result.filename()).isEqualTo("trends.csv");
        assertThat(result.rowCount()).isEqualTo(2);
        verify(repository).saveImport(result);
    }

    @Test
    void rejectsFormulaLikeInterestAndMissingRequiredHeaders() {
        GoogleTrendsCsvService service = new GoogleTrendsCsvService(
                properties(), mock(DiscoveryService.class), mock(DiscoveryRepository.class));
        MockMultipartFile formula = new MockMultipartFile("file", "bad.csv", "text/csv",
                "Query,Interest\nTrend,=1+1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockMultipartFile missing = new MockMultipartFile("file", "bad.csv", "text/csv",
                "Query,Date\nTrend,2026\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.importCsv(formula, "US", "AI_DEV_TOOLS", markets()))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("numeric Google Trends value");
        assertThatThrownBy(() -> service.importCsv(missing, "US", "AI_DEV_TOOLS", markets()))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("must contain a query/search term");
    }

    private static DiscoveryProperties properties() {
        return new DiscoveryProperties(20, 1024, 1024, Duration.ofSeconds(1), Duration.ofMinutes(1),
                List.of("technology"), java.util.Map.of(), new DiscoveryProperties.Gdelt(false,
                "https://api.gdeltproject.org/api/v2/doc/doc", Duration.ZERO),
                new DiscoveryProperties.Youtube(false, "YOUTUBE_API_KEY", 2, 10000, 30),
                new DiscoveryProperties.Rss(false));
    }

    private static List<CatalogProperties.Market> markets() {
        return List.of(new CatalogProperties.Market("US", "United States", "US", "en", "America/New_York"));
    }
}
