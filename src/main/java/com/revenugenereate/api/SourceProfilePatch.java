package com.revenugenereate.api;

import com.revenugenereate.config.CatalogProperties;
import com.revenugenereate.config.ValidHttpUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;
import java.util.List;

public record SourceProfilePatch(
        Boolean enabled,
        List<@ValidHttpUrl String> endpointUrls,
        @Valid CatalogProperties.Permissions permissions,
        @ValidHttpUrl String termsUrl,
        LocalDate termsReviewDate,
        @PositiveOrZero Integer retentionDays,
        List<String> requiredApprovals,
        List<@Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "must be an environment variable name")
                        String> secretEnvironmentVariables,
        String redditCommercialPermissionReference,
        String youtubeDerivedMetricsApprovalReference) {
}
