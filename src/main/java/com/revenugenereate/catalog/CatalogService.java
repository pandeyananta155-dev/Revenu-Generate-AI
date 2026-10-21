package com.revenugenereate.catalog;

import com.revenugenereate.api.ApiRequestException;
import com.revenugenereate.api.OfferCreateRequest;
import com.revenugenereate.api.OfferPatch;
import com.revenugenereate.api.SourceProfilePatch;
import com.revenugenereate.config.AppProperties;
import com.revenugenereate.config.CatalogProperties;
import com.revenugenereate.config.HttpUrlValidator;
import com.revenugenereate.persistence.CatalogRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {

    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_-]{1,63}");

    private final AppProperties properties;
    private final CatalogRepository repository;
    private final ProviderPolicyGuard policyGuard;

    public CatalogService(
            AppProperties properties, CatalogRepository repository, ProviderPolicyGuard policyGuard) {
        this.properties = properties;
        this.repository = repository;
        this.policyGuard = policyGuard;
    }

    public List<CatalogProperties.Market> getMarkets() {
        return properties.catalogs().markets();
    }

    public List<CatalogProperties.Niche> getNiches() {
        return properties.catalogs().niches();
    }

    public List<SourceProfile> getProviders() {
        return repository.findAllProviders();
    }

    public SourceProfile getProvider(String code) {
        return repository.findProvider(normalizeCode(code))
                .orElseThrow(() -> notFound("Source profile", code));
    }

    public SourceProfile createProvider(CatalogProperties.Provider request) {
        policyGuard.validate(request);
        if (repository.findProvider(request.code()).isPresent()) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "duplicate-provider", "A source profile with this code already exists");
        }
        SourceProfile profile = SourceProfile.from(request);
        repository.insertProvider(profile);
        return getProvider(profile.code());
    }

    public SourceProfile patchProvider(String code, SourceProfilePatch patch) {
        SourceProfile current = getProvider(code);
        CatalogProperties.Provider updated = new CatalogProperties.Provider(
                current.code(),
                current.name(),
                current.sourceType(),
                choose(patch.endpointUrls(), current.endpointUrls()),
                choose(patch.enabled(), current.enabled()),
                choose(patch.permissions(), current.permissions()),
                choose(patch.termsUrl(), current.termsUrl()),
                choose(patch.termsReviewDate(), current.termsReviewDate()),
                choose(patch.retentionDays(), current.retentionDays()),
                choose(patch.requiredApprovals(), current.requiredApprovals()),
                choose(patch.secretEnvironmentVariables(), current.secretEnvironmentVariables()),
                choose(patch.redditCommercialPermissionReference(), current.redditCommercialPermissionReference()),
                choose(patch.youtubeDerivedMetricsApprovalReference(),
                        current.youtubeDerivedMetricsApprovalReference()));
        validateProviderPatch(updated);
        policyGuard.validate(updated);
        repository.updateProvider(SourceProfile.from(updated));
        return getProvider(code);
    }

    public List<OfferRecord> getOffers() {
        return repository.findAllOffers();
    }

    public OfferRecord getOffer(String code) {
        return repository.findOffer(normalizeCode(code))
                .orElseThrow(() -> notFound("Offer", code));
    }

    public OfferRecord createOffer(OfferCreateRequest request) {
        validateOfferFields(request.code(), request.destinationUrl(), request.supportedCountries(),
                request.approvedClaims(), request.termsReviewDate(), request.name(), request.disclosure());
        if (repository.findOffer(request.code()).isPresent()) {
            throw new ApiRequestException(HttpStatus.CONFLICT, "duplicate-offer", "An offer with this code already exists");
        }
        repository.insertOffer(new OfferRecord(
                request.code(), request.name(), request.destinationUrl(), request.supportedCountries(),
                request.approvedClaims(), request.termsReviewDate(), request.disclosure(),
                request.enabled(), null, null));
        return getOffer(request.code());
    }

    public OfferRecord patchOffer(String code, OfferPatch patch) {
        OfferRecord current = getOffer(code);
        OfferRecord updated = new OfferRecord(
                current.code(),
                choose(patch.name(), current.name()),
                choose(patch.destinationUrl(), current.destinationUrl()),
                choose(patch.supportedCountries(), current.supportedCountries()),
                choose(patch.approvedClaims(), current.approvedClaims()),
                choose(patch.termsReviewDate(), current.termsReviewDate()),
                choose(patch.disclosure(), current.disclosure()),
                choose(patch.enabled(), current.enabled()),
                current.createdAt(),
                current.updatedAt());
        validateOfferFields(updated.code(), updated.destinationUrl(), updated.supportedCountries(),
                updated.approvedClaims(), updated.termsReviewDate(), updated.name(), updated.disclosure());
        repository.updateOffer(updated);
        return getOffer(code);
    }

    private void validateProviderPatch(CatalogProperties.Provider provider) {
        if (!CODE_PATTERN.matcher(provider.code()).matches()) {
            throw badRequest("Provider code must match [A-Z][A-Z0-9_-]{1,63}");
        }
        if (provider.termsUrl() == null || !isHttpUrl(provider.termsUrl())) {
            throw badRequest("termsUrl must be an absolute HTTP or HTTPS URL");
        }
        if (provider.termsReviewDate() == null) {
            throw badRequest("termsReviewDate is required");
        }
        if (provider.retentionDays() != null && provider.retentionDays() < 0) {
            throw badRequest("retentionDays cannot be negative");
        }
        if (provider.permissions() == null) {
            throw badRequest("permissions are required");
        }
    }

    private void validateOfferFields(
            String code,
            String destinationUrl,
            List<String> countries,
            List<String> claims,
            LocalDate termsReviewDate,
            String name,
            String disclosure) {
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw badRequest("Offer code must match [A-Z][A-Z0-9_-]{1,63}");
        }
        if (name == null || name.isBlank() || disclosure == null || disclosure.isBlank()) {
            throw badRequest("Offer name and disclosure are required");
        }
        if (!isHttpUrl(destinationUrl)) {
            throw badRequest("destinationUrl must be an absolute HTTP or HTTPS URL");
        }
        if (termsReviewDate == null) {
            throw badRequest("termsReviewDate is required");
        }
        if (countries == null || countries.isEmpty()
                || countries.stream().anyMatch(country -> country == null
                        || country.length() != 2
                        || !List.of(Locale.getISOCountries()).contains(country))) {
            throw badRequest("supportedCountries must contain ISO 3166-1 alpha-2 codes");
        }
        if (claims == null || claims.isEmpty() || claims.stream().anyMatch(claim -> claim == null || claim.isBlank())) {
            throw badRequest("At least one non-blank approved claim is required");
        }
    }

    private String normalizeCode(String code) {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw badRequest("Identifier must match [A-Z][A-Z0-9_-]{1,63}");
        }
        return code;
    }

    private static boolean isHttpUrl(String value) {
        return HttpUrlValidator.isSafeHttpUrl(value);
    }

    private static ApiRequestException notFound(String type, String code) {
        return new ApiRequestException(HttpStatus.NOT_FOUND, "not-found", type + " '" + code + "' was not found");
    }

    private static ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "invalid-request", message);
    }

    private static <T> T choose(T replacement, T existing) {
        return replacement == null ? existing : replacement;
    }
}
