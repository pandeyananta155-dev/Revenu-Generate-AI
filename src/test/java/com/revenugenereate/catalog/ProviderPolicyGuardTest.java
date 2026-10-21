package com.revenugenereate.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revenugenereate.api.ApiRequestException;
import com.revenugenereate.config.CatalogProperties;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderPolicyGuardTest {

    private final ProviderPolicyGuard guard = new ProviderPolicyGuard();

    @Test
    void redditHostCannotBeMadeCommercialByChangingSourceType() {
        var provider = provider(
                "PUBLISHER_FEED",
                "RSS_ATOM",
                List.of("https://www.reddit.com/r/example/.rss"),
                true,
                new CatalogProperties.Permissions(true, true, true, false),
                null,
                null);

        assertThatThrownBy(() -> guard.validate(provider))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("written permission reference");
    }

    @Test
    void youtubeAnalyticsRequireApprovalEvenWhenSourceTypeIsMislabelled() {
        var provider = provider(
                "VIDEO_WATCHLIST",
                "RSS_ATOM",
                List.of("https://www.googleapis.com/youtube/v3/channels"),
                false,
                new CatalogProperties.Permissions(true, true, false, false),
                null,
                null);

        assertThatThrownBy(() -> guard.validate(provider))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("accepted approval reference");
    }

    @Test
    void mediaDownloadIsAlwaysRejectedForPublicProviders() {
        var provider = provider(
                "PUBLIC_FEED",
                "RSS_ATOM",
                List.of("https://example.com/feed.xml"),
                false,
                new CatalogProperties.Permissions(true, false, false, true),
                null,
                null);

        assertThatThrownBy(() -> guard.validate(provider))
                .isInstanceOf(ApiRequestException.class)
                .hasMessageContaining("mediaDownload cannot be enabled");
    }

    private static CatalogProperties.Provider provider(
            String code,
            String sourceType,
            List<String> endpoints,
            boolean enabled,
            CatalogProperties.Permissions permissions,
            String redditReference,
            String youtubeReference) {
        return new CatalogProperties.Provider(
                code,
                code,
                sourceType,
                endpoints,
                enabled,
                permissions,
                "https://example.com/terms",
                LocalDate.now(),
                30,
                List.of(),
                List.of(),
                redditReference,
                youtubeReference);
    }
}
