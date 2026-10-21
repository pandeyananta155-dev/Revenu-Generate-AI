package com.revenugenereate.watchlist;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable API and persistence-facing records for watchlist sources, refreshes, and personal notes. */
public final class WatchlistModels {
    private WatchlistModels() {}

    /** A configured YouTube source, with its operational state and timestamps. */
    public record Source(
            String id, String sourceType, String sourceUrl, String externalSourceId, String displayName,
            String countryCode, String nicheCode, List<String> keywords, int priority, boolean enabled,
            int refreshIntervalHours, String sourceStatus, Instant lastCheckedAt,
            Instant lastSuccessfulCheckAt, String lastError, Instant createdAt, Instant updatedAt,
            Instant archivedAt) {}

    /** One bounded API refresh attempt; YouTube metadata is retained for at most 30 days. */
    public record RefreshHistory(
            String id, String sourceId, Instant checkedAt, String outcome, int recordsProcessed,
            String message, List<String> requestReferences, List<RefreshItem> records, Instant deleteAfter) {}

    /** Minimal provider-native metadata returned by a manual refresh. */
    public record RefreshItem(
            String videoId, String title, String sourceUrl, Instant publishedAt, String channelId,
            String channelTitle, Map<String, Long> nativeStatistics) {}

    /** A personal note linked to an optional watched source. */
    public record ResearchNote(
            String id, String sourceId, String title, String noteText, String relatedSourceUrl,
            String countryCode, String nicheCode, List<String> tags, Instant createdAt, Instant updatedAt) {}

    /** Non-secret readiness state for official YouTube API access. */
    public record ProviderReadiness(String code, String status, String reason) {}

    /** Stable page envelope used by the watchlist and research-note listing APIs. */
    public record Page<T>(List<T> items, int page, int size, long total) {}
}
