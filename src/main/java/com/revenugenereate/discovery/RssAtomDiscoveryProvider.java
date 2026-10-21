package com.revenugenereate.discovery;

import com.revenugenereate.catalog.SourceProfile;
import com.revenugenereate.discovery.DiscoveryModels.ProviderStatus;
import com.revenugenereate.persistence.CatalogRepository;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/** Reads only configured RSS and Atom endpoints, with bounded responses and conditional caching. */
@Component
public class RssAtomDiscoveryProvider implements DiscoveryProvider {
    private final CatalogRepository catalog;
    private final DiscoveryProperties properties;
    private final HttpClient client;
    private final Map<String, FeedCache> caches = new HashMap<>();

    public RssAtomDiscoveryProvider(
            CatalogRepository catalog, DiscoveryProperties properties, HttpClient discoveryHttpClient) {
        this.catalog = catalog;
        this.properties = properties;
        this.client = discoveryHttpClient;
    }

    @Override public String code() { return "OFFICIAL_FEEDS"; }

    @Override
    public List<String> supportedCodes() {
        return configuredProfiles().stream().map(SourceProfile::code).distinct().toList();
    }

    @Override
    public ProviderStatus status() {
        boolean available = configuredProfiles().stream()
                .anyMatch(profile -> profile.enabled() && !profile.endpointUrls().isEmpty());
        return new ProviderStatus(code(), "Configured RSS and Atom feeds", available ? "AVAILABLE" : "DISABLED",
                available ? "Only configured and enabled feed URLs are queried."
                        : "Enable a source profile of type RSS_ATOM or GOOGLE_TRENDS and configure an approved endpoint.");
    }

    @Override
    public synchronized List<ProviderItem> discover(ProviderContext context) throws Exception {
        List<ProviderItem> items = new ArrayList<>();
        for (SourceProfile profile : configuredProfiles()) {
            if (!profile.enabled()) continue;
            if (!profile.code().equals(context.requestedProviderCode())) continue;
            if (!"RSS_ATOM".equals(profile.sourceType()) && !"GOOGLE_TRENDS".equals(profile.sourceType())) continue;
            for (String endpoint : profile.endpointUrls()) {
                List<ProviderItem> feedItems = retrieve(profile.code(), profile.name(), endpoint, context.maxResults());
                for (ProviderItem item : feedItems) {
                    if (items.size() >= context.maxResults()) return items;
                    items.add(new ProviderItem(item.recordId(), item.title(), item.excerpt(), item.url(),
                            profile.name(), item.publishedAt(), item.language(), null, null, null,
                            "Configured publisher feed", List.of(
                                    "Feed availability, publication dates, and content are controlled by the publisher.",
                                    "Country indicates the requested target market, not verified audience geography.")));
                }
            }
        }
        return items;
    }

    private List<SourceProfile> configuredProfiles() {
        return catalog.findAllProviders().stream()
                .filter(profile -> "RSS_ATOM".equals(profile.sourceType())
                        || "GOOGLE_TRENDS".equals(profile.sourceType()))
                .toList();
    }

    private List<ProviderItem> retrieve(String sourceCode, String name, String endpoint, int maxResults)
            throws Exception {
        FeedCache previous = caches.get(sourceCode + "|" + endpoint);
        Instant now = Instant.now();
        if (previous != null && previous.lastFetched().plus(properties.rssRefreshInterval()).isAfter(now)) {
            return previous.items().stream().limit(maxResults).toList();
        }
        URI uri = RemoteHttpSafety.validate(URI.create(endpoint), properties.rss().allowPrivateAddressesForDevelopment());
        for (int redirect = 0; redirect <= 2; redirect++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(properties.requestTimeout())
                    .header("Accept", "application/atom+xml, application/rss+xml, application/xml, text/xml")
                    .GET();
            if (previous != null) {
                if (previous.etag() != null) builder.header("If-None-Match", previous.etag());
                if (previous.lastModified() != null) builder.header("If-Modified-Since", previous.lastModified());
            }
            HttpResponse<byte[]> response =
                    client.send(builder.build(), RemoteHttpSafety.boundedBodyHandler(properties.maxFeedBytes()));
            if (response.statusCode() == 304 && previous != null) {
                FeedCache refreshed = new FeedCache(previous.etag(), previous.lastModified(), now, previous.items());
                caches.put(sourceCode + "|" + endpoint, refreshed);
                return refreshed.items().stream().limit(maxResults).toList();
            }
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IllegalStateException("Feed redirect has no Location header"));
                if (redirect == 2) throw new IllegalStateException("Feed redirect limit exceeded");
                uri = RemoteHttpSafety.validate(uri.resolve(location),
                        properties.rss().allowPrivateAddressesForDevelopment());
                continue;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Feed " + name + " returned HTTP " + response.statusCode());
            }
            List<ProviderItem> items = parse(response.body(), uri, maxResults);
            FeedCache cache = new FeedCache(response.headers().firstValue("ETag").orElse(null),
                    response.headers().firstValue("Last-Modified").orElse(null), now, items);
            caches.put(sourceCode + "|" + endpoint, cache);
            return items;
        }
        throw new IllegalStateException("Feed redirect limit exceeded");
    }

    private static List<ProviderItem> parse(byte[] body, URI feedUri, int maxResults) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8.name());
        List<ProviderItem> items = new ArrayList<>();
        Entry entry = null;
        String field = null;
        while (reader.hasNext() && items.size() < maxResults) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String local = reader.getLocalName().toLowerCase(java.util.Locale.ROOT);
                if ("item".equals(local) || "entry".equals(local)) {
                    entry = new Entry();
                } else if (entry != null && ("title".equals(local) || "description".equals(local)
                        || "summary".equals(local) || "content".equals(local) || "pubdate".equals(local)
                        || "published".equals(local) || "updated".equals(local) || "id".equals(local)
                        || "guid".equals(local) || "link".equals(local))) {
                    field = local;
                    if ("link".equals(local)) {
                        String href = reader.getAttributeValue(null, "href");
                        if (href != null) entry.url = feedUri.resolve(href).toString();
                    }
                }
            } else if (event == XMLStreamConstants.CHARACTERS && entry != null && field != null) {
                String text = reader.getText();
                if (!text.isBlank()) entry.put(field, text);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String local = reader.getLocalName().toLowerCase(java.util.Locale.ROOT);
                if (entry != null && ("item".equals(local) || "entry".equals(local))) {
                    String url = entry.url == null ? entry.id : entry.url;
                    if (url != null && entry.title != null) {
                        Instant published = parsePublished(entry.published);
                        if (!isHttpUrl(url)) {
                            entry = null;
                            continue;
                        }
                        items.add(new ProviderItem(url, entry.title, truncate(entry.excerpt, 500), url, null,
                                published, null, null, null, null, "RSS/Atom feed entry",
                                List.of("Publisher metadata has not been independently verified.")));
                    }
                    entry = null;
                }
                field = null;
            }
        }
        reader.close();
        return List.copyOf(items);
    }

    private static Instant parsePublished(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); }
        catch (java.time.format.DateTimeParseException ignored) {
            try { return java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant(); }
            catch (java.time.format.DateTimeParseException ignoredAgain) { return null; }
        }
    }

    private static String truncate(String text, int max) {
        return text == null ? null : text.substring(0, Math.min(text.length(), max));
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record FeedCache(String etag, String lastModified, Instant lastFetched, List<ProviderItem> items) {}

    private static final class Entry {
        private String title;
        private String excerpt;
        private String published;
        private String id;
        private String url;
        void put(String field, String text) {
            switch (field) {
                case "title" -> title = append(title, text, 500);
                case "description", "summary", "content" -> excerpt = append(excerpt, text, 2000);
                case "pubdate", "published", "updated" -> published = append(published, text, 100);
                case "id", "guid" -> id = append(id, text, 2048);
                case "link" -> { if (url == null) url = text; }
                default -> { }
            }
        }

        private static String append(String current, String value, int max) {
            String combined = (current == null ? "" : current) + value;
            return truncate(combined, max);
        }
    }
}
