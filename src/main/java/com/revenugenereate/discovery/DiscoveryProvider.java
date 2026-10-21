package com.revenugenereate.discovery;

import com.revenugenereate.discovery.DiscoveryModels.ProviderStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Contract implemented by each independently failing discovery source. */
public interface DiscoveryProvider {
    /** Stable provider code used in API responses and persisted evidence. */
    String code();

    /** All source-profile codes this adapter can serve. */
    default List<String> supportedCodes() { return List.of(code()); }

    /** Current availability and policy status for discovery. */
    ProviderStatus status();

    /** Fetches attributed evidence for the supplied request context. */
    List<ProviderItem> discover(ProviderContext context) throws Exception;

    /** Provider-specific input, safely normalized into source-neutral evidence. */
    /** An attributed provider row with provider-native metrics kept in separate fields. */
    record ProviderItem(
            String recordId,
            String title,
            String excerpt,
            String url,
            String sourceName,
            Instant publishedAt,
            String language,
            Map<String, Long> youtubeNativeMetrics,
            String googleTrendsNativeInterest,
            Integer nativeRank,
            String provenance,
            List<String> limitations) {}

    /** Market and query context passed to a provider, including the actual country semantics. */
    /** Explicit market and niche inputs passed to one provider invocation. */
    record ProviderContext(
            String marketCode,
            String countryCode,
            String nicheCode,
            List<String> queryTerms,
            Instant since,
            int maxResults,
            String requestedProviderCode) {}
}
