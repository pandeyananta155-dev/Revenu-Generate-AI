package com.revenugenereate.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.revenugenereate.api.ApiRequestException;
import com.revenugenereate.config.AppProperties;
import com.revenugenereate.config.CatalogProperties;
import com.revenugenereate.discovery.DiscoveryProperties;
import com.revenugenereate.discovery.DiscoveryRepository;
import com.revenugenereate.discovery.DiscoveryModels.ProviderStatus;
import com.revenugenereate.discovery.YouTubeDiscoveryProvider;
import com.revenugenereate.watchlist.WatchlistModels.RefreshHistory;
import com.revenugenereate.watchlist.WatchlistModels.RefreshItem;
import com.revenugenereate.watchlist.WatchlistModels.Source;
import com.revenugenereate.watchlist.YouTubeWatchlistClient.RefreshResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WatchlistServiceTest {
    @Test
    void disabledSourceIsNeverRefreshed() throws Exception {
        WatchlistRepository repository = mock(WatchlistRepository.class);
        DiscoveryRepository quota = mock(DiscoveryRepository.class);
        YouTubeWatchlistClient client = mock(YouTubeWatchlistClient.class);
        WatchlistService service = service(repository, quota, client, availableProvider());
        Source source = source(false, "YOUTUBE_VIDEO");
        when(repository.findSource(source.id())).thenReturn(java.util.Optional.of(source));

        assertThatThrownBy(() -> service.refresh(source.id()))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("Disabled sources");
        verify(client, never()).refresh(any(), any(), anyInt());
        verify(quota, never()).reserveYoutubeQuota(anyInt(), anyInt());
    }

    @Test
    void successfulRefreshStoresCurrentProviderRowsAndQuotaIsReserved() throws Exception {
        WatchlistRepository repository = mock(WatchlistRepository.class);
        DiscoveryRepository quota = mock(DiscoveryRepository.class);
        YouTubeWatchlistClient client = mock(YouTubeWatchlistClient.class);
        WatchlistService service = service(repository, quota, client, availableProvider());
        Source source = source(true, "YOUTUBE_VIDEO");
        when(repository.findSource(source.id())).thenReturn(java.util.Optional.of(source));
        when(quota.reserveYoutubeQuota(1, 10000)).thenReturn(true);
        RefreshItem item = new RefreshItem("dQw4w9WgXcQ", "Example title",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ", Instant.parse("2026-10-20T12:00:00Z"),
                "UC1234567890123456789012", "Example channel", Map.of("viewCount", 42L));
        when(client.refresh(eq(source), isNull(), eq(25))).thenReturn(new RefreshResponse(
                List.of(item), List.of("videos.list"), null, "Fetched current metadata."));

        RefreshHistory result = service.refresh(source.id());

        assertThat(result.outcome()).isEqualTo("SUCCEEDED");
        assertThat(result.records()).containsExactly(item);
        assertThat(result.deleteAfter()).isAfter(result.checkedAt()).isEqualTo(result.checkedAt().plus(Duration.ofDays(30)));
        verify(quota).reserveYoutubeQuota(1, 10000);
        verify(repository).recordCheck(source, result.checkedAt(), true, null, null);
        verify(repository).saveHistory(result);
    }

    @Test
    void quotaAndTimeoutFailuresAreRecordedWithoutClaimingSuccess() throws Exception {
        WatchlistRepository repository = mock(WatchlistRepository.class);
        DiscoveryRepository quota = mock(DiscoveryRepository.class);
        YouTubeWatchlistClient client = mock(YouTubeWatchlistClient.class);
        WatchlistService service = service(repository, quota, client, availableProvider());
        Source source = source(true, "YOUTUBE_CHANNEL");
        when(repository.findSource(source.id())).thenReturn(java.util.Optional.of(source));
        when(quota.reserveYoutubeQuota(3, 10000)).thenReturn(false);

        RefreshHistory quotaResult = service.refresh(source.id());

        assertThat(quotaResult.outcome()).isEqualTo("FAILED");
        assertThat(quotaResult.message()).contains("quota budget exhausted");
        verify(client, never()).refresh(any(), any(), anyInt());
        verify(repository).recordCheck(source, quotaResult.checkedAt(), false, quotaResult.message(), null);

        when(quota.reserveYoutubeQuota(3, 10000)).thenReturn(true);
        when(client.refresh(eq(source), isNull(), eq(25))).thenThrow(new HttpTimeoutException("request timed out"));
        RefreshHistory timeoutResult = service.refresh(source.id());
        assertThat(timeoutResult.outcome()).isEqualTo("FAILED");
        assertThat(timeoutResult.message()).isEqualTo("YouTube API request timed out");
        verify(repository).recordCheck(source, timeoutResult.checkedAt(), false, timeoutResult.message(), null);
    }

    private static WatchlistService service(WatchlistRepository repository, DiscoveryRepository quota,
            YouTubeWatchlistClient client, YouTubeDiscoveryProvider provider) {
        CatalogProperties catalogs = new CatalogProperties(
                List.of(new CatalogProperties.Market("US", "United States", "US", "en", "America/New_York")),
                List.of(new CatalogProperties.Niche("TECH_GENERAL", "Technology", "Technology topics")),
                List.of(), List.of(), new CatalogProperties.Watchlist(List.of()));
        AppProperties app = new AppProperties("Test", "0.1", "target", catalogs);
        DiscoveryProperties discovery = new DiscoveryProperties(20, 1048576, 1048576,
                Duration.ofSeconds(1), Duration.ZERO, List.of("technology"), Map.of(),
                new DiscoveryProperties.Gdelt(false, "https://api.gdeltproject.org/api/v2/doc/doc", Duration.ZERO),
                new DiscoveryProperties.Youtube(true, "YOUTUBE_API_KEY", 2, 10000, 30),
                new DiscoveryProperties.Rss(false));
        return new WatchlistService(app, repository, new WatchlistProperties(25, 30, List.of()), discovery,
                quota, provider, client);
    }

    private static YouTubeDiscoveryProvider availableProvider() {
        YouTubeDiscoveryProvider provider = mock(YouTubeDiscoveryProvider.class);
        when(provider.status()).thenReturn(new ProviderStatus("YOUTUBE_WATCHLIST", "YouTube Data API", "AVAILABLE", "Ready"));
        return provider;
    }

    private static Source source(boolean enabled, String type) {
        Instant now = Instant.parse("2026-10-21T12:00:00Z");
        return new Source("a43f30e7-b520-4f40-9935-d3ded2749aae", type,
                type.equals("YOUTUBE_VIDEO") ? "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
                        : "https://www.youtube.com/channel/UC1234567890123456789012",
                type.equals("YOUTUBE_VIDEO") ? "dQw4w9WgXcQ" : "UC1234567890123456789012",
                "Test source", "US", "TECH_GENERAL", List.of("tools"), 3, enabled, 24,
                enabled ? "PENDING" : "DISABLED", null, null, null, now, now, null);
    }
}
