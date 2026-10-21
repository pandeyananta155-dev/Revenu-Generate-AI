package com.revenugenereate.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogPropertiesValidationTest {

    @Test
    void reportsInvalidCountryTimezoneUrlsRetentionAndDuplicateCodes() {
        var invalidMarket = new CatalogProperties.Market(
                "US", "United States", "ZZ", "en", "Mars/Phobos");
        var invalidProvider = new CatalogProperties.Provider(
                "TEST_PROVIDER",
                "Test provider",
                "RSS_ATOM",
                List.of("https://example.com/feed.xml?api_key=secret-value"),
                false,
                new CatalogProperties.Permissions(false, false, false, false),
                "not a URL",
                LocalDate.now(),
                -1,
                List.of(),
                List.of(),
                null,
                null);
        var offer = new CatalogProperties.Offer(
                "TEST_OFFER", "Test offer", "https://example.com", List.of("US"),
                List.of("Claim"), LocalDate.now(), "Disclosure", false);
        var duplicateOffer = new CatalogProperties.Offer(
                "TEST_OFFER", "Duplicate", "https://example.com", List.of("US"),
                List.of("Claim"), LocalDate.now(), "Disclosure", false);
        var catalogs = new CatalogProperties(
                List.of(invalidMarket),
                List.of(new CatalogProperties.Niche("TEST", "Test", "Test niche")),
                List.of(invalidProvider),
                List.of(offer, duplicateOffer),
                new CatalogProperties.Watchlist(List.of()));

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var messages = factory.getValidator().validate(catalogs).stream()
                    .map(violation -> violation.getMessage())
                    .toList();

            assertThat(messages)
                    .anyMatch(message -> message.contains("valid ISO 3166"))
                    .anyMatch(message -> message.contains("valid IANA timezone"))
                    .anyMatch(message -> message.contains("absolute HTTP or HTTPS"))
                    .anyMatch(message -> message.contains("must be greater than or equal to 0"))
                    .anyMatch(message -> message.contains("unique codes"));
        }
    }

    @Test
    void rejectsMissingProviderConfigurationFields() {
        var incompleteProvider = new CatalogProperties.Provider(
                null, null, null, null, null, null, null, null, null, null, null, null, null);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var violations = factory.getValidator().validate(incompleteProvider);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "code",
                            "name",
                            "sourceType",
                            "endpointUrls",
                            "enabled",
                            "permissions",
                            "termsUrl",
                            "termsReviewDate",
                            "retentionDays",
                            "requiredApprovals",
                            "secretEnvironmentVariables");
        }
    }
}
