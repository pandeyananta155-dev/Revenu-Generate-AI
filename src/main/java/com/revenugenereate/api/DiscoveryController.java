package com.revenugenereate.api;

import com.revenugenereate.config.AppProperties;
import com.revenugenereate.discovery.DiscoveryModels.ProviderStatus;
import com.revenugenereate.discovery.DiscoveryModels.ResultPage;
import com.revenugenereate.discovery.DiscoveryModels.Scan;
import com.revenugenereate.discovery.DiscoveryModels.ScanRequest;
import com.revenugenereate.discovery.DiscoveryModels.TrendsImportResponse;
import com.revenugenereate.discovery.DiscoveryService;
import com.revenugenereate.discovery.GoogleTrendsCsvService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** REST endpoints for provider discovery status, scan execution, results, and CSV imports. */
@Validated
@RestController
@RequestMapping("/api/v1/discovery")
@Tag(name = "Trend discovery", description = "Source-attributed discovery evidence and provider availability.")
public class DiscoveryController {
    private final DiscoveryService discoveryService;
    private final GoogleTrendsCsvService csvService;
    private final AppProperties appProperties;

    public DiscoveryController(
            DiscoveryService discoveryService, GoogleTrendsCsvService csvService, AppProperties appProperties) {
        this.discoveryService = discoveryService;
        this.csvService = csvService;
        this.appProperties = appProperties;
    }

    /** Lists configured provider availability and policy blocks. */
    @GetMapping("/providers")
    @Operation(summary = "List discovery providers", description = "Reports enabled, disabled, unconfigured, and policy-blocked providers.")
    public List<ProviderStatus> providers() {
        return discoveryService.providerStatuses();
    }

    /** Starts a bounded synchronous scan; provider failures are reported per provider. */
    @PostMapping("/scans")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a discovery scan", description = "Runs configured providers independently and retains source attribution.")
    public Scan createScan(@Valid @RequestBody ScanRequest request) {
        return discoveryService.runScan(request);
    }

    /** Returns one persisted scan summary. */
    @GetMapping("/scans/{scanId}")
    @Operation(summary = "Get a discovery scan")
    public Scan getScan(@PathVariable String scanId) {
        return discoveryService.getScan(scanId);
    }

    /** Returns filtered and paginated evidence for one scan. */
    @GetMapping("/scans/{scanId}/results")
    @Operation(summary = "List discovery results")
    public ResultPage getResults(
            @PathVariable String scanId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z]{2}") String country,
            @RequestParam(required = false) @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String niche,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return discoveryService.getResults(scanId, provider, country, niche, page, size);
    }

    /** Accepts a bounded Google Trends CSV export with explicit country and niche context. */
    @PostMapping(value = "/google-trends/import", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Import a Google Trends CSV", description = "Accepts a user-provided CSV; does not scrape Google Trends or execute formulas.")
    public TrendsImportResponse importGoogleTrendsCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @RequestParam @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String nicheCode) {
        return csvService.importCsv(file, countryCode, nicheCode, appProperties.catalogs().markets());
    }
}
