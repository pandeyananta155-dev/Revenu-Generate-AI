package com.revenugenereate.catalog;

import com.revenugenereate.api.ApiRequestException;
import com.revenugenereate.config.CatalogProperties;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProviderPolicyGuard {

    public void validate(CatalogProperties.Provider provider) {
        var permissions = provider.permissions();
        if (permissions != null && permissions.mediaDownload()) {
            throw rejected("mediaDownload cannot be enabled for provider profiles or public URLs");
        }

        boolean commercialUse = provider.enabled()
                || (permissions != null && (permissions.scoring() || permissions.contentGrounding()));
        if (isRedditProvider(provider)
                && commercialUse
                && isBlank(provider.redditCommercialPermissionReference())) {
            throw rejected("Reddit commercial use requires a written permission reference");
        }

        if (isYoutubeProvider(provider)
                && permissions != null
                && (permissions.scoring() || permissions.contentGrounding())
                && isBlank(provider.youtubeDerivedMetricsApprovalReference())) {
            throw rejected("YouTube-derived analytics require an accepted approval reference");
        }
    }

    private static ApiRequestException rejected(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "provider-policy-rejected", message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isRedditProvider(CatalogProperties.Provider provider) {
        return "REDDIT".equalsIgnoreCase(provider.sourceType())
                || provider.code().toUpperCase(Locale.ROOT).contains("REDDIT")
                || provider.endpointUrls().stream().anyMatch(ProviderPolicyGuard::isRedditUrl);
    }

    private static boolean isYoutubeProvider(CatalogProperties.Provider provider) {
        return "YOUTUBE_API".equalsIgnoreCase(provider.sourceType())
                || provider.code().toUpperCase(Locale.ROOT).contains("YOUTUBE")
                || provider.endpointUrls().stream().anyMatch(ProviderPolicyGuard::isYoutubeUrl);
    }

    private static boolean isRedditUrl(String value) {
        return hostEndsWith(value, "reddit.com");
    }

    private static boolean isYoutubeUrl(String value) {
        if (hostEndsWith(value, "youtube.com") || hostEndsWith(value, "youtube.googleapis.com")) {
            return true;
        }
        try {
            URI uri = URI.create(value);
            return hostEndsWith(value, "googleapis.com")
                    && uri.getPath() != null
                    && uri.getPath().toLowerCase(Locale.ROOT).contains("/youtube/");
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean hostEndsWith(String value, String domain) {
        try {
            String host = URI.create(value).getHost();
            return host != null
                    && (host.equalsIgnoreCase(domain) || host.toLowerCase(Locale.ROOT).endsWith("." + domain));
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
