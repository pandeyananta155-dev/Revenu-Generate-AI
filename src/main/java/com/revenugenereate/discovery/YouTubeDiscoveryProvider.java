package com.revenugenereate.discovery;

import com.revenugenereate.catalog.SourceProfile;
import com.revenugenereate.discovery.DiscoveryModels.ProviderStatus;
import com.revenugenereate.persistence.CatalogRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Optional official YouTube Data API search with native metrics and a persistent daily quota cap. */
@Component
public class YouTubeDiscoveryProvider implements DiscoveryProvider {
    private static final String API_ROOT = "https://www.googleapis.com/youtube/v3/";
    private final DiscoveryProperties properties;
    private final CatalogRepository catalog;
    private final DiscoveryRepository repository;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public YouTubeDiscoveryProvider(
            DiscoveryProperties properties,
            CatalogRepository catalog,
            DiscoveryRepository repository,
            HttpClient discoveryHttpClient,
            ObjectMapper mapper) {
        this.properties = properties;
        this.catalog = catalog;
        this.repository = repository;
        this.client = discoveryHttpClient;
        this.mapper = mapper;
    }

    @Override public String code() { return "YOUTUBE_WATCHLIST"; }

    @Override
    public ProviderStatus status() {
        SourceProfile profile = catalog.findProvider(code()).orElse(null);
        String key = System.getenv(properties.youtube().apiKeyEnvironmentVariable());
        if (!properties.youtube().enabled() || profile == null || !profile.enabled()) {
            return new ProviderStatus(code(), "YouTube Data API", "DISABLED",
                    "Enable discovery.youtube.enabled and the YOUTUBE_WATCHLIST source profile.");
        }
        if (key == null || key.isBlank()) {
            return new ProviderStatus(code(), "YouTube Data API", "UNCONFIGURED",
                    "The API key environment variable is not set; key values are never returned.");
        }
        if (properties.youtube().maxRequestsPerScan() < 2) {
            return new ProviderStatus(code(), "YouTube Data API", "BLOCKED",
                    "At least two bounded API calls per scan are required for search and native statistics.");
        }
        return new ProviderStatus(code(), "YouTube Data API", "AVAILABLE",
                "Official API metadata only; native metrics are retained separately and never combined into a score.");
    }

    @Override
    public List<ProviderItem> discover(ProviderContext context) throws Exception {
        ProviderStatus current = status();
        if (!"AVAILABLE".equals(current.status())) return List.of();
        String key = System.getenv(properties.youtube().apiKeyEnvironmentVariable());
        if (!repository.reserveYoutubeQuota(101, properties.youtube().dailyQuotaUnitBudget())) {
            throw new IllegalStateException("Daily YouTube quota-unit budget exhausted");
        }
        String terms = String.join(" ", context.queryTerms());
        String searchUrl = API_ROOT + "search?part=snippet&type=video&maxResults="
                + Math.min(25, context.maxResults()) + "&regionCode=" + encode(context.countryCode())
                + "&q=" + encode(terms) + "&key=" + encode(key);
        SearchResponse search = get(searchUrl, SearchResponse.class);
        if (search.items() == null || search.items().isEmpty()) return List.of();
        String ids = search.items().stream().map(item -> item.id() == null ? null : item.id().videoId())
                .filter(id -> id != null && !id.isBlank()).distinct().limit(context.maxResults())
                .collect(java.util.stream.Collectors.joining(","));
        if (ids.isBlank()) return List.of();
        VideoResponse videos = get(API_ROOT + "videos?part=statistics&id=" + encode(ids) + "&key=" + encode(key),
                VideoResponse.class);
        Map<String, Map<String, Long>> metrics = new HashMap<>();
        if (videos.items() != null) {
            for (Video video : videos.items()) {
                Map<String, Long> nativeFields = new HashMap<>();
                put(nativeFields, "viewCount", video.statistics() == null ? null : video.statistics().viewCount());
                put(nativeFields, "likeCount", video.statistics() == null ? null : video.statistics().likeCount());
                put(nativeFields, "commentCount", video.statistics() == null ? null : video.statistics().commentCount());
                metrics.put(video.id(), Map.copyOf(nativeFields));
            }
        }
        List<ProviderItem> results = new ArrayList<>();
        for (SearchItem item : search.items()) {
            if (item.id() == null || item.id().videoId() == null || item.snippet() == null) continue;
            String id = item.id().videoId();
            results.add(new ProviderItem(id, item.snippet().title(), null,
                    "https://www.youtube.com/watch?v=" + encode(id), item.snippet().channelTitle(),
                    item.snippet().publishedAt(), null, metrics.getOrDefault(id, Map.of()), null, null,
                    "Official YouTube Data API v3 search and videos resources",
                    List.of("Metrics are raw platform-native counts; they are not a cross-provider score.",
                            "Regional search context is not proof that every viewer is in the requested country.")));
            if (results.size() >= context.maxResults()) break;
        }
        return results;
    }

    private <T> T get(String url, Class<T> responseType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(properties.requestTimeout())
                .header("Accept", "application/json").GET().build();
        HttpResponse<byte[]> response =
                client.send(request, RemoteHttpSafety.boundedBodyHandler(properties.maxFeedBytes()));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("YouTube Data API returned HTTP " + response.statusCode());
        }
        try {
            return mapper.readValue(response.body(), responseType);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("YouTube Data API returned an invalid JSON response", exception);
        }
    }

    private static void put(Map<String, Long> target, String key, Long value) {
        if (value != null) target.put(key, value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record SearchResponse(List<SearchItem> items) {}
    private record SearchItem(VideoId id, Snippet snippet) {}
    private record VideoId(String videoId) {}
    private record Snippet(String title, String channelTitle, Instant publishedAt) {}
    private record VideoResponse(List<Video> items) {}
    private record Video(String id, Statistics statistics) {}
    private record Statistics(Long viewCount, Long likeCount, Long commentCount) {}
}
