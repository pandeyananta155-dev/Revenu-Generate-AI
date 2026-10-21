package com.revenugenereate.discovery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Source-neutral and provider-status records exposed by the discovery API. */
public final class DiscoveryModels {
    private DiscoveryModels() {}

    /** A bounded request to discover signals for one configured market and niche. */
    public record ScanRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{2,32}") String marketCode,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String nicheCode,
            @Min(1) @Max(30) int windowDays,
            @jakarta.validation.constraints.Size(max = 10)
                    List<@Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String> providers,
            @Min(1) @Max(200) Integer maxResults) {}

    /** Provider availability is explicit, including blocked providers. */
    public record ProviderStatus(String code, String name, String status, String reason) {}

    /** A single provider attempt and its isolated outcome. */
    public record ProviderSummary(String providerCode, String status, int resultCount, String message) {}

    /** One attributed, normalized source item; native YouTube metrics are kept separate from other evidence. */
    public record Result(
            String id,
            String providerCode,
            String providerRecordId,
            String countryCode,
            String countrySemantics,
            String nicheCode,
            List<String> queryTerms,
            String title,
            String excerpt,
            String sourceUrl,
            String sourceName,
            Instant publishedAt,
            Instant fetchedAt,
            String language,
            Map<String, Long> youtubeNativeMetrics,
            String googleTrendsNativeInterest,
            Integer nativeRank,
            String provenance,
            List<String> limitations,
            String processingStatus,
            Instant deleteAfter) {}

    /** A persisted scan and the result count per provider. */
    public record Scan(
            String id,
            String marketCode,
            String countryCode,
            String nicheCode,
            int windowDays,
            String status,
            Instant startedAt,
            Instant completedAt,
            List<ProviderSummary> providers,
            int resultCount) {}

    /** Paginated result envelope. */
    public record ResultPage(List<Result> items, int page, int size, long total) {}

    /** A bounded, explicit CSV import with non-inferred market and niche context. */
    public record TrendsImportRequest(
            @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String nicheCode) {}

    /** Import acknowledgment with source attribution retained. */
    public record TrendsImportResponse(
            String id, String scanId, String filename, String countryCode, String nicheCode,
            String timeWindow, int rowCount, String sourceLabel, Instant importedAt, String attribution) {}

    /** Parsed, attributed row from an uploaded Google Trends CSV. */
    public record TrendsRow(
            String query,
            String searchInterest,
            Integer nativeRank,
            String countryCode,
            String nicheCode,
            String timeWindow,
            String filename,
            Instant importedAt) {}
}
