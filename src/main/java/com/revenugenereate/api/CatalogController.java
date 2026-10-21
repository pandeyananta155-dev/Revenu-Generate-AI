package com.revenugenereate.api;

import com.revenugenereate.catalog.CatalogService;
import com.revenugenereate.catalog.OfferRecord;
import com.revenugenereate.catalog.SourceProfile;
import com.revenugenereate.config.CatalogProperties;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Configuration catalogs", description = "Configured markets, niches, providers, and offers")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/markets")
    @Operation(summary = "List configured markets")
    @ApiResponse(responseCode = "200", description = "Configured markets", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = CatalogProperties.Market.class)),
            examples = @ExampleObject(value = """
                    [{"code":"US","name":"United States","countryCode":"US","language":"en","timezone":"America/New_York"}]
                    """)))
    public List<CatalogProperties.Market> markets() {
        return catalogService.getMarkets();
    }

    @GetMapping("/niches")
    @Operation(summary = "List configured niches")
    @ApiResponse(responseCode = "200", description = "Configured niches", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = CatalogProperties.Niche.class)),
            examples = @ExampleObject(value = """
                    [{"code":"AI_DEV_TOOLS","name":"AI and Developer Tools","description":"AI tools and developer workflows"}]
                    """)))
    public List<CatalogProperties.Niche> niches() {
        return catalogService.getNiches();
    }

    @GetMapping("/providers")
    @Operation(summary = "List source profiles")
    @ApiResponse(responseCode = "200", description = "Configured source profiles", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = SourceProfile.class)),
            examples = @ExampleObject(value = """
                    [{"code":"OFFICIAL_FEEDS","name":"Approved publisher feeds","sourceType":"RSS_ATOM","endpointUrls":[],"enabled":false,"permissions":{"display":true,"scoring":true,"contentGrounding":true,"mediaDownload":false},"termsUrl":"https://example.com/terms","termsReviewDate":"2026-10-21","retentionDays":30,"requiredApprovals":[],"secretEnvironmentVariables":[]}]
                    """)))
    public List<SourceProfile> providers() {
        return catalogService.getProviders();
    }

    @GetMapping("/providers/{code}")
    @Operation(summary = "Get one source profile")
    @ApiResponse(responseCode = "200", description = "Source profile", content = @Content(
            schema = @Schema(implementation = SourceProfile.class),
            examples = @ExampleObject(value = """
                    {"code":"OFFICIAL_FEEDS","name":"Approved publisher feeds","sourceType":"RSS_ATOM","endpointUrls":[],"enabled":false,"permissions":{"display":true,"scoring":true,"contentGrounding":true,"mediaDownload":false},"termsUrl":"https://example.com/terms","termsReviewDate":"2026-10-21","retentionDays":30,"requiredApprovals":[],"secretEnvironmentVariables":[]}
                    """)))
    public SourceProfile provider(@PathVariable String code) {
        return catalogService.getProvider(code);
    }

    @PostMapping("/providers")
    @Operation(summary = "Create a source profile", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    schema = @Schema(implementation = CatalogProperties.Provider.class),
                    examples = @ExampleObject(value = """
                            {"code":"PUBLISHER_FEED","name":"Publisher feed","sourceType":"RSS_ATOM","endpointUrls":["https://example.com/feed.xml"],"enabled":false,"permissions":{"display":true,"scoring":false,"contentGrounding":false,"mediaDownload":false},"termsUrl":"https://example.com/terms","termsReviewDate":"2026-10-21","retentionDays":30,"requiredApprovals":[],"secretEnvironmentVariables":[]}
                            """))))
    @ApiResponse(responseCode = "201", description = "Source profile created", content = @Content(
            schema = @Schema(implementation = SourceProfile.class)))
    public ResponseEntity<SourceProfile> createProvider(
            @Valid @RequestBody CatalogProperties.Provider request) {
        SourceProfile created = catalogService.createProvider(request);
        return ResponseEntity.created(URI.create("/api/v1/providers/" + created.code())).body(created);
    }

    @PatchMapping("/providers/{code}")
    @Operation(summary = "Update allowed source profile settings", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    schema = @Schema(implementation = SourceProfilePatch.class),
                    examples = @ExampleObject(value = """
                            {"enabled":true,"retentionDays":14}
                            """))))
    @ApiResponse(responseCode = "200", description = "Updated source profile", content = @Content(
            schema = @Schema(implementation = SourceProfile.class)))
    public SourceProfile patchProvider(
            @PathVariable String code, @Valid @RequestBody SourceProfilePatch patch) {
        return catalogService.patchProvider(code, patch);
    }

    @GetMapping("/offers")
    @Operation(summary = "List configured offers")
    @ApiResponse(responseCode = "200", description = "Configured offers", content = @Content(
            array = @ArraySchema(schema = @Schema(implementation = OfferRecord.class)),
            examples = @ExampleObject(value = """
                    [{"code":"FREE_WORKFLOW_CHECKLIST","name":"Free workflow checklist","destinationUrl":"https://example.com/checklist","supportedCountries":["US"],"approvedClaims":["Free checklist"],"termsReviewDate":"2026-10-21","disclosure":"Free resource.","enabled":false}]
                    """)))
    public List<OfferRecord> offers() {
        return catalogService.getOffers();
    }

    @GetMapping("/offers/{code}")
    @Operation(summary = "Get one offer")
    @ApiResponse(responseCode = "200", description = "Offer", content = @Content(
            schema = @Schema(implementation = OfferRecord.class),
            examples = @ExampleObject(value = """
                    {"code":"FREE_WORKFLOW_CHECKLIST","name":"Free workflow checklist","destinationUrl":"https://example.com/checklist","supportedCountries":["US"],"approvedClaims":["Free checklist"],"termsReviewDate":"2026-10-21","disclosure":"Free resource.","enabled":false}
                    """)))
    public OfferRecord offer(@PathVariable String code) {
        return catalogService.getOffer(code);
    }

    @PostMapping("/offers")
    @Operation(summary = "Create an offer", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    schema = @Schema(implementation = OfferCreateRequest.class),
                    examples = @ExampleObject(value = """
                            {"code":"STARTER_TEMPLATE","name":"Starter template","destinationUrl":"https://example.com/template","supportedCountries":["US"],"approvedClaims":["A reviewed factual claim"],"termsReviewDate":"2026-10-21","disclosure":"Affiliate disclosure if applicable.","enabled":false}
                            """))))
    @ApiResponse(responseCode = "201", description = "Offer created", content = @Content(
            schema = @Schema(implementation = OfferRecord.class)))
    public ResponseEntity<OfferRecord> createOffer(@Valid @RequestBody OfferCreateRequest request) {
        OfferRecord created = catalogService.createOffer(request);
        return ResponseEntity.created(URI.create("/api/v1/offers/" + created.code())).body(created);
    }

    @PatchMapping("/offers/{code}")
    @Operation(summary = "Update an offer", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    schema = @Schema(implementation = OfferPatch.class),
                    examples = @ExampleObject(value = """
                            {"enabled":true,"disclosure":"Updated disclosure"}
                            """))))
    @ApiResponse(responseCode = "200", description = "Updated offer", content = @Content(
            schema = @Schema(implementation = OfferRecord.class)))
    public OfferRecord patchOffer(@PathVariable String code, @Valid @RequestBody OfferPatch patch) {
        return catalogService.patchOffer(code, patch);
    }
}
