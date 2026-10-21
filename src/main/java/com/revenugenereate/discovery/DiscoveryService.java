package com.revenugenereate.discovery;

import com.revenugenereate.api.ApiRequestException;
import com.revenugenereate.config.AppProperties;
import com.revenugenereate.config.CatalogProperties;
import com.revenugenereate.discovery.DiscoveryModels.ProviderStatus;
import com.revenugenereate.discovery.DiscoveryModels.Result;
import com.revenugenereate.discovery.DiscoveryModels.ResultPage;
import com.revenugenereate.discovery.DiscoveryModels.Scan;
import com.revenugenereate.discovery.DiscoveryModels.ScanRequest;
import com.revenugenereate.discovery.DiscoveryModels.ProviderSummary;
import com.revenugenereate.discovery.DiscoveryProvider.ProviderContext;
import com.revenugenereate.persistence.CatalogRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates isolated provider attempts and persists source-attributed discovery evidence. */
@Service
public class DiscoveryService {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryService.class);
    private static final String REDDIT_CODE = "REDDIT_RESEARCH";

    private final CatalogProperties catalogs;
    private final CatalogRepository catalogRepository;
    private final DiscoveryProperties properties;
    private final DiscoveryRepository repository;
    private final List<DiscoveryProvider> providers;

    public DiscoveryService(
            AppProperties appProperties,
            CatalogRepository catalogRepository,
            DiscoveryProperties properties,
            DiscoveryRepository repository,
            List<DiscoveryProvider> providers) {
        this.catalogs = appProperties.catalogs();
        this.catalogRepository = catalogRepository;
        this.properties = properties;
        this.repository = repository;
        this.providers = List.copyOf(providers);
    }

    /** Reports current availability and policy for each configured discovery provider. */
    public List<ProviderStatus> providerStatuses() {
        List<ProviderStatus> statuses = new ArrayList<>();
        for (var profile : catalogRepository.findAllProviders()) {
            if ("RSS_ATOM".equals(profile.sourceType()) || "GOOGLE_TRENDS".equals(profile.sourceType())) {
                statuses.add(new ProviderStatus(profile.code(), profile.name(),
                        profile.enabled() && !profile.endpointUrls().isEmpty() ? "AVAILABLE" : "DISABLED",
                        profile.enabled() && !profile.endpointUrls().isEmpty() ? "Only configured feed endpoints are fetched."
                                : "Enable this source profile and configure approved feed endpoints."));
            } else if ("GDELT_NEWS".equals(profile.code())) {
                statuses.add(findStatus(profile.code()));
            } else if ("YOUTUBE_WATCHLIST".equals(profile.code())) {
                statuses.add(findStatus(profile.code()));
            } else if ("REDDIT".equals(profile.sourceType()) || REDDIT_CODE.equals(profile.code())) {
                statuses.add(new ProviderStatus(profile.code(), profile.name(), "BLOCKED",
                        "No Reddit network calls are made; commercial use requires written permission and approved credentials."));
            }
        }
        java.util.Set<String> included = statuses.stream().map(ProviderStatus::code)
                .collect(java.util.stream.Collectors.toSet());
        for (DiscoveryProvider adapter : providers) {
            for (String code : adapter.supportedCodes()) {
                if (!included.contains(code)) statuses.add(findStatus(code));
            }
        }
        statuses.add(new ProviderStatus("GOOGLE_TRENDS_CSV", "Google Trends CSV import", "AVAILABLE",
                "Imports are user-supplied, bounded, attributed exports; this app does not scrape Google Trends."));
        return List.copyOf(statuses);
    }

    /** Runs configured providers independently so one failure cannot discard other providers' results. */
    @Transactional
    public Scan runScan(ScanRequest request) {
        repository.deleteExpiredResults(Instant.now());
        CatalogProperties.Market market = catalogs.markets().stream()
                .filter(value -> value.code().equals(request.marketCode())).findFirst()
                .orElseThrow(() -> badRequest("Unknown market code"));
        CatalogProperties.Niche niche = catalogs.niches().stream()
                .filter(value -> value.code().equals(request.nicheCode())).findFirst()
                .orElseThrow(() -> badRequest("Unknown niche code"));
        int maxResults = Math.min(
                request.maxResults() == null ? properties.maxResultsPerScan() : request.maxResults(),
                properties.maxResultsPerScan());
        List<String> configuredTerms = properties.nicheKeywords() == null
                ? null : properties.nicheKeywords().get(niche.code());
        List<String> terms = configuredTerms == null || configuredTerms.isEmpty()
                ? properties.defaultKeywords() : configuredTerms;
        String scanId = UUID.randomUUID().toString();
        Instant started = Instant.now();
        Instant since = started.minus(request.windowDays(), ChronoUnit.DAYS);
        List<String> requestedCodes = request.providers() == null || request.providers().isEmpty()
                ? providerStatuses().stream().filter(status -> "AVAILABLE".equals(status.status()))
                        .map(ProviderStatus::code).filter(providerByCode()::containsKey).toList()
                : request.providers();
        List<ProviderSummary> summaries = new ArrayList<>();
        List<Result> results = new ArrayList<>();
        for (String code : requestedCodes.stream().distinct().toList()) {
            if (REDDIT_CODE.equals(code) || "REDDIT".equalsIgnoreCase(code)) {
                summaries.add(new ProviderSummary(code, "BLOCKED", 0,
                        "No request was made because Reddit commercial use is blocked by policy."));
                continue;
            }
            DiscoveryProvider provider = providerByCode().get(code);
            if (provider == null) throw badRequest("Unknown or unsupported discovery provider: " + code);
            ProviderStatus status = providerStatuses().stream().filter(item -> item.code().equals(code))
                    .findFirst().orElse(null);
            if (status == null || !"AVAILABLE".equals(status.status())) {
                summaries.add(new ProviderSummary(code, status == null ? "DISABLED" : status.status(), 0,
                        status == null ? "Provider is not configured." : status.reason()));
                continue;
            }
            ProviderContext context = new ProviderContext(
                    market.code(), market.countryCode(), niche.code(), terms, since, maxResults, code);
            long providerStartedNanos = System.nanoTime();
            try {
                List<DiscoveryProvider.ProviderItem> items = provider.discover(context);
                List<Result> normalized = new ArrayList<>();
                for (DiscoveryProvider.ProviderItem item : items) {
                    if (results.size() + normalized.size() >= maxResults) break;
                    // Apply the requested window when a source supplies an original timestamp;
                    // undated evidence remains available without inventing a publication date.
                    if (item.publishedAt() != null && item.publishedAt().isBefore(since)) continue;
                    normalized.add(normalize(item, code, market, niche, terms, started));
                }
                results.addAll(normalized);
                summaries.add(new ProviderSummary(code, "COMPLETED", normalized.size(), "Provider completed successfully."));
                logger.info("Discovery scan {} provider {} completed with {} saved results in {} ms",
                        scanId, code, normalized.size(), elapsedMillis(providerStartedNanos));
            } catch (Exception exception) {
                logger.warn("Discovery scan {} provider {} failed ({}) after {} ms",
                        scanId, code, exception.getClass().getSimpleName(), elapsedMillis(providerStartedNanos));
                summaries.add(new ProviderSummary(code, "FAILED", 0,
                        "Provider failed; results from other providers were retained."));
            }
        }
        String status = summaries.stream().anyMatch(item -> "FAILED".equals(item.status()))
                ? (summaries.stream().anyMatch(item -> "COMPLETED".equals(item.status())) ? "PARTIAL" : "FAILED")
                : results.isEmpty() ? "NO_RESULTS" : "COMPLETED";
        Instant completed = Instant.now();
        Scan scan = new Scan(scanId, market.code(), market.countryCode(), niche.code(),
                request.windowDays(), status, started, completed, summaries, results.size());
        repository.save(scan, results);
        return scan;
    }

    /** Returns a stored scan by identifier. */
    public Scan getScan(String id) {
        repository.deleteExpiredResults(Instant.now());
        return repository.findScan(id)
                .orElseThrow(() -> new ApiRequestException(HttpStatus.NOT_FOUND, "scan-not-found", "Discovery scan was not found"));
    }

    /** Returns filtered, paginated evidence for a scan. */
    public ResultPage getResults(
            String scanId, String provider, String country, String niche, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw badRequest("Page must be non-negative and size must be between 1 and 100");
        getScan(scanId);
        long total = repository.countResults(scanId, provider, country, niche);
        List<Result> results = repository.findResults(
                scanId, provider, country, niche, size, Math.multiplyExact((long) page, size));
        return new ResultPage(results, page, size, total);
    }

    /** Persists a CSV import scan and its attributed results atomically. */
    @Transactional
    public Scan saveImportedResults(
            String marketCode, String countryCode, String nicheCode, String timeWindow,
            List<DiscoveryProvider.ProviderItem> items) {
        CatalogProperties.Niche niche = catalogs.niches().stream()
                .filter(value -> value.code().equals(nicheCode)).findFirst()
                .orElseThrow(() -> badRequest("Unknown niche code"));
        CatalogProperties.Market market = catalogs.markets().stream()
                .filter(value -> value.code().equals(marketCode)).findFirst()
                .orElseThrow(() -> badRequest("Unknown market code"));
        if (!market.countryCode().equals(countryCode)) {
            throw badRequest("Import country must match the selected configured market");
        }
        Instant now = Instant.now();
        List<Result> results = new ArrayList<>();
        for (DiscoveryProvider.ProviderItem item : items) {
            results.add(normalize(item, "GOOGLE_TRENDS_CSV", market, niche, List.of(item.title()), now));
        }
        Scan scan = new Scan(UUID.randomUUID().toString(), market.code(), countryCode, niche.code(), 1,
                "COMPLETED", now, now, List.of(new ProviderSummary("GOOGLE_TRENDS_CSV",
                        "COMPLETED", results.size(), "Imported user-supplied Google Trends CSV data.")),
                results.size());
        repository.save(scan, results);
        return scan;
    }

    private Result normalize(
            DiscoveryProvider.ProviderItem item, String providerCode, CatalogProperties.Market market,
            CatalogProperties.Niche niche, List<String> terms, Instant fetchedAt) {
        if (!isHttpUrl(item.url())) {
            throw new IllegalArgumentException("Provider returned a non-HTTP source URL");
        }
        int retentionDays = providerCode.equals("YOUTUBE_WATCHLIST")
                ? properties.youtube().retentionDays()
                : catalogRepository.findProvider(providerCode).map(profile -> profile.retentionDays() == null
                        ? 30 : profile.retentionDays()).orElse(30);
        Instant deleteAfter = fetchedAt.plus(retentionDays, ChronoUnit.DAYS);
        String semantics = switch (providerCode) {
            case "GDELT_NEWS" -> "Requested market " + market.code()
                    + "; GDELT source-country filter " + market.countryCode();
            case "YOUTUBE_WATCHLIST" -> "YouTube regional search context " + market.countryCode();
            case "GOOGLE_TRENDS_CSV" -> "User-provided export market " + market.countryCode();
            default -> "Requested target market " + market.countryCode() + "; audience geography unverified";
        };
        return new Result(UUID.randomUUID().toString(), providerCode, item.recordId(), market.countryCode(),
                semantics, niche.code(), List.copyOf(terms), item.title(), item.excerpt(), item.url(),
                item.sourceName(), item.publishedAt(), fetchedAt, item.language(), item.youtubeNativeMetrics(),
                item.googleTrendsNativeInterest(), item.nativeRank(), item.provenance(), item.limitations(),
                "UNREVIEWED", deleteAfter);
    }

    private ProviderStatus findStatus(String code) {
        DiscoveryProvider provider = providerByCode().get(code);
        return provider == null
                ? new ProviderStatus(code, code, "DISABLED", "No provider adapter is registered.")
                : provider.status();
    }

    private Map<String, DiscoveryProvider> providerByCode() {
        Map<String, DiscoveryProvider> adapters = new HashMap<>();
        for (DiscoveryProvider provider : providers) {
            for (String code : provider.supportedCodes()) adapters.put(code, provider);
        }
        return adapters;
    }

    private static ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "invalid-discovery-request", message);
    }

    private static boolean isHttpUrl(String value) {
        if (value == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
