package com.revenugenereate.catalog;

import com.revenugenereate.config.CatalogProperties;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record SourceProfile(
        String code,
        String name,
        String sourceType,
        List<String> endpointUrls,
        boolean enabled,
        CatalogProperties.Permissions permissions,
        String termsUrl,
        LocalDate termsReviewDate,
        Integer retentionDays,
        List<String> requiredApprovals,
        List<String> secretEnvironmentVariables,
        String redditCommercialPermissionReference,
        String youtubeDerivedMetricsApprovalReference,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static SourceProfile from(CatalogProperties.Provider provider) {
        return new SourceProfile(
                provider.code(),
                provider.name(),
                provider.sourceType(),
                provider.endpointUrls(),
                provider.enabled(),
                provider.permissions(),
                provider.termsUrl(),
                provider.termsReviewDate(),
                provider.retentionDays(),
                provider.requiredApprovals(),
                provider.secretEnvironmentVariables(),
                provider.redditCommercialPermissionReference(),
                provider.youtubeDerivedMetricsApprovalReference(),
                null,
                null);
    }

    public SourceProfile withTimestamps(LocalDateTime created, LocalDateTime updated) {
        return new SourceProfile(
                code, name, sourceType, endpointUrls, enabled, permissions, termsUrl, termsReviewDate,
                retentionDays, requiredApprovals, secretEnvironmentVariables,
                redditCommercialPermissionReference, youtubeDerivedMetricsApprovalReference,
                created, updated);
    }
}
