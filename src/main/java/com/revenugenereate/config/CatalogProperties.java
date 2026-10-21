package com.revenugenereate.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record CatalogProperties(
        @NotEmpty List<@NotNull @Valid Market> markets,
        @NotEmpty List<@NotNull @Valid Niche> niches,
        @NotEmpty List<@NotNull @Valid Provider> providers,
        @NotEmpty List<@NotNull @Valid Offer> offers,
        @Valid Watchlist youtubeWatchlist) {

    @AssertTrue(message = "app.catalogs.markets must contain unique codes")
    public boolean isMarketCodesUnique() {
        return markets == null || markets.stream().anyMatch(Objects::isNull)
                || hasUniqueCodes(markets.stream().map(Market::code).toList());
    }

    @AssertTrue(message = "app.catalogs.niches must contain unique codes")
    public boolean isNicheCodesUnique() {
        return niches == null || niches.stream().anyMatch(Objects::isNull)
                || hasUniqueCodes(niches.stream().map(Niche::code).toList());
    }

    @AssertTrue(message = "app.catalogs.providers must contain unique codes")
    public boolean isProviderCodesUnique() {
        return providers == null || providers.stream().anyMatch(Objects::isNull)
                || hasUniqueCodes(providers.stream().map(Provider::code).toList());
    }

    @AssertTrue(message = "app.catalogs.offers must contain unique codes")
    public boolean isOfferCodesUnique() {
        return offers == null || offers.stream().anyMatch(Objects::isNull)
                || hasUniqueCodes(offers.stream().map(Offer::code).toList());
    }

    private static boolean hasUniqueCodes(List<String> codes) {
        return codes.stream().allMatch(code -> code != null)
                && codes.stream().map(String::toUpperCase).distinct().count() == codes.size();
    }

    public record Market(
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{2,32}", message = "must be an uppercase market code")
                    String code,
            @NotBlank String name,
            @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "must be a two-letter ISO 3166-1 country code")
                    String countryCode,
            @NotBlank @Pattern(regexp = "[a-z]{2,3}(-[A-Z]{2})?", message = "must be a valid language tag")
                    String language,
            @NotBlank String timezone) {

        @AssertTrue(message = "app.catalogs.markets[].country-code must be a valid ISO 3166-1 alpha-2 code")
        public boolean isCountryCodeKnown() {
            return countryCode == null || List.of(Locale.getISOCountries()).contains(countryCode);
        }

        @AssertTrue(message = "app.catalogs.markets[].timezone must be a valid IANA timezone such as Asia/Kathmandu")
        public boolean isTimezoneKnown() {
            if (timezone == null) {
                return true;
            }
            try {
                return ZoneId.getAvailableZoneIds().contains(ZoneId.of(timezone).getId());
            } catch (RuntimeException exception) {
                return false;
            }
        }
    }

    public record Niche(    @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}", message = "must be an uppercase niche code")
            String code,
    @NotBlank String name,
    @NotBlank String description) {
    }

    public record Permissions(
            @NotNull Boolean display,
            @NotNull Boolean scoring,
            @NotNull Boolean contentGrounding,
            @NotNull Boolean mediaDownload) {
    }

    public record Provider(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}", message = "must be an uppercase provider code")
                String code,
            @NotBlank String name,
            @NotBlank @Pattern(
                        regexp = "GOOGLE_TRENDS|RSS_ATOM|MANUAL|YOUTUBE_API|REDDIT|GDELT",
                        message = "must be a supported source type")
                String sourceType,
            @NotNull List<@ValidHttpUrl String> endpointUrls,
            @NotNull Boolean enabled,
            @NotNull @Valid Permissions permissions,
            @NotBlank @ValidHttpUrl String termsUrl,
            @jakarta.validation.constraints.NotNull(message = "terms-review-date is required")
                    LocalDate termsReviewDate,
            @NotNull @jakarta.validation.constraints.PositiveOrZero Integer retentionDays,
            @NotNull List<@NotBlank String> requiredApprovals,
            @NotNull List<@Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "must be an environment variable name")
                    String> secretEnvironmentVariables,
            String redditCommercialPermissionReference,
            String youtubeDerivedMetricsApprovalReference) {

        @AssertTrue(message = "media-download permission is disabled for public URL providers")
        public boolean isMediaDownloadDisabled() {
            return permissions == null || !permissions.mediaDownload();
        }

        @AssertTrue(message = "terms-url must use HTTP or HTTPS")
        public boolean isTermsUrlHttp() {
            return termsUrl == null || isHttpUrl(termsUrl);
        }

        private static boolean isHttpUrl(String value) {
            try {
                URI uri = URI.create(value);
                return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null;
            } catch (IllegalArgumentException exception) {
                return false;
            }
        }
    }

    public record Offer(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}", message = "must be an uppercase offer code")
                    String code,
            @NotBlank String name,
            @NotBlank @ValidHttpUrl String destinationUrl,
            @NotEmpty List<@Pattern(regexp = "[A-Z]{2}", message = "must be an ISO country code")
                            String> supportedCountries,
            @NotEmpty List<@NotBlank String> approvedClaims,
            @jakarta.validation.constraints.NotNull(message = "terms-review-date is required")
                    LocalDate termsReviewDate,
            @NotBlank String disclosure,
            @NotNull Boolean enabled) {

        @AssertTrue(message = "app.catalogs.offers[].supported-countries must contain ISO 3166-1 alpha-2 codes")
        public boolean areSupportedCountriesKnown() {
            return supportedCountries == null
                    || supportedCountries.stream().allMatch(country ->
                            country != null && List.of(Locale.getISOCountries()).contains(country));
        }
    }

    public record Watchlist(
            @NotNull List<@NotBlank @Pattern(
                            regexp = "UC[A-Za-z0-9_-]{22}",
                            message = "must be a YouTube channel ID")
                    String> channelIds) {
    }
}
