package com.revenugenereate.discovery;

import com.revenugenereate.discovery.DiscoveryModels.ProviderStatus;
import com.revenugenereate.persistence.CatalogRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Retrieves article-list evidence from the documented public GDELT DOC API. */
@Component
public class GdeltDiscoveryProvider implements DiscoveryProvider {
    private final DiscoveryProperties properties;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final CatalogRepository catalog;
    private long lastRequestNanos;

    public GdeltDiscoveryProvider(
            DiscoveryProperties properties, HttpClient discoveryHttpClient, ObjectMapper mapper,
            CatalogRepository catalog) {
        this.properties = properties;
        this.client = discoveryHttpClient;
        this.mapper = mapper;
        this.catalog = catalog;
    }

    @Override public String code() { return "GDELT_NEWS"; }

    @Override
    public ProviderStatus status() {
        boolean profileEnabled = catalog.findProvider(code()).map(p -> p.enabled()).orElse(false);
        boolean enabled = profileEnabled && properties.gdelt().enabled();
        return new ProviderStatus(code(), "GDELT DOC API", enabled ? "AVAILABLE" : "DISABLED",
                enabled ? "News article evidence only; no audience metric or composite score."
                        : "Enable discovery.gdelt.enabled and the GDELT_NEWS source profile.");
    }

    @Override
    public List<ProviderItem> discover(ProviderContext context) throws Exception {
        if (!properties.gdelt().enabled()) return List.of();
        awaitRateLimit();
        String query = context.queryTerms().isEmpty() ? "technology"
                : context.queryTerms().stream().limit(5).map(term -> "\"" + term.replace("\"", "") + "\"")
                        .collect(java.util.stream.Collectors.joining(" OR "));
        query += " sourcecountry:" + context.countryCode();
        String params = "query=" + encode(query) + "&mode=artlist&format=json&maxrecords="
                + Math.min(context.maxResults(), 50) + "&startdatetime="
                + context.since().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + "&enddatetime=" + Instant.now().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        URI endpoint = URI.create(properties.gdelt().baseUrl() + "?" + params);
        HttpRequest request = HttpRequest.newBuilder(RemoteHttpSafety.validate(endpoint, false))
                .timeout(properties.requestTimeout()).header("Accept", "application/json").GET().build();
        HttpResponse<byte[]> response = client.send(
                request, RemoteHttpSafety.boundedBodyHandler(properties.maxFeedBytes()));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GDELT returned HTTP " + response.statusCode());
        }

        final GdeltResponse payload;
        try {
            payload = mapper.readValue(response.body(), GdeltResponse.class);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("GDELT returned an invalid JSON response", exception);
        }
        List<ProviderItem> items = new ArrayList<>();
        if (payload.articles() == null) return items;
        for (GdeltArticle article : payload.articles()) {
            if (items.size() >= context.maxResults() || article.url() == null || article.title() == null) break;
            items.add(new ProviderItem(article.url(), article.title(), null, article.url(), article.domain(),
                    parseDate(article.seendate()), article.language(), null, null, null,
                    "GDELT DOC API article-list result", List.of(
                            "GDELT records news coverage, not social engagement or demand.",
                            "Source country filter reflects publisher/source metadata, not target-market readership.")));
        }
        return items;
    }

    private synchronized void awaitRateLimit() throws InterruptedException {
        long intervalNanos = properties.gdelt().requestInterval().toNanos();
        long elapsed = System.nanoTime() - lastRequestNanos;
        long remaining = intervalNanos - elapsed;
        if (lastRequestNanos != 0 && remaining > 0) {
            TimeUnit.NANOSECONDS.sleep(remaining);
        }
        lastRequestNanos = System.nanoTime();
    }

    private static Instant parseDate(String value) {
        if (value == null || value.length() < 8) return null;
        try {
            if (value.length() >= 14) {
                return java.time.LocalDateTime.parse(value.substring(0, 14),
                                DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                        .toInstant(ZoneOffset.UTC);
            }
            return LocalDate.parse(value.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE)
                    .atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException exception) {
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record GdeltResponse(List<GdeltArticle> articles) {}
    private record GdeltArticle(String url, String title, String seendate, String domain, String language,
                                String sourcecountry) {}
}
