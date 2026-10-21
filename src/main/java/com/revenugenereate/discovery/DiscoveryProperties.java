package com.revenugenereate.discovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validated operational limits and feature switches for local discovery providers.
 *
 * @param maxResultsPerScan hard cap for normalized results in one scan
 * @param maxCsvBytes maximum size accepted for a Google Trends CSV import
 * @param maxFeedBytes maximum response size accepted from any remote provider
 * @param requestTimeout connection/read deadline for provider requests
 * @param rssRefreshInterval minimum interval between requests to one feed
 * @param nicheKeywords configured query words by niche code
 * @param gdelt GDELT endpoint settings
 * @param youtube official YouTube Data API settings
 * @param rss RSS network safety settings
 */
@Validated
@ConfigurationProperties(prefix = "discovery")
public record DiscoveryProperties(
        @Min(1) @Max(200) int maxResultsPerScan,
        @Min(1024) @Max(5242880) int maxCsvBytes,
        @Min(1024) @Max(5242880) int maxFeedBytes,
        @NotNull Duration requestTimeout,
        @NotNull Duration rssRefreshInterval,
        List<String> defaultKeywords,
        Map<String, List<String>> nicheKeywords,
        @Valid Gdelt gdelt,
        @Valid Youtube youtube,
        @Valid Rss rss) {

    /** GDELT DOC API settings. Its result text is news coverage, not an audience signal. */
    public record Gdelt(
            boolean enabled,
            @NotBlank String baseUrl,
            @NotNull Duration requestInterval) {
        public Gdelt {
            if (baseUrl != null && !isHttpsUrl(baseUrl)) {
                throw new IllegalArgumentException("discovery.gdelt.base-url must be an absolute HTTPS URL");
            }
        }
    }

    /** Official YouTube Data API quota and retention limits. */
    public record Youtube(
            boolean enabled,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String apiKeyEnvironmentVariable,
            @Min(1) @Max(2) int maxRequestsPerScan,
            @Min(101) @Max(100000) int dailyQuotaUnitBudget,
            @Min(1) @Max(30) int retentionDays) {
    }

    /** RSS network-safety controls. Private destinations are denied except in explicit local development. */
    public record Rss(boolean allowPrivateAddressesForDevelopment) {
    }

    private static boolean isHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
