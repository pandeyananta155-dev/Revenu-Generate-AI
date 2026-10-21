package com.revenugenereate.catalog;

import com.revenugenereate.config.CatalogProperties;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record OfferRecord(
        String code,
        String name,
        String destinationUrl,
        List<String> supportedCountries,
        List<String> approvedClaims,
        LocalDate termsReviewDate,
        String disclosure,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static OfferRecord from(CatalogProperties.Offer offer) {
        return new OfferRecord(
                offer.code(), offer.name(), offer.destinationUrl(), offer.supportedCountries(),
                offer.approvedClaims(), offer.termsReviewDate(), offer.disclosure(), offer.enabled(),
                null, null);
    }

    public OfferRecord withTimestamps(LocalDateTime created, LocalDateTime updated) {
        return new OfferRecord(
                code, name, destinationUrl, supportedCountries, approvedClaims, termsReviewDate,
                disclosure, enabled, created, updated);
    }
}
