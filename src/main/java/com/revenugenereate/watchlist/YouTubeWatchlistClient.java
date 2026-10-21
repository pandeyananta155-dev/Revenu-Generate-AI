package com.revenugenereate.watchlist;

import com.revenugenereate.discovery.DiscoveryProperties;
import com.revenugenereate.watchlist.WatchlistModels.RefreshItem;
import com.revenugenereate.watchlist.WatchlistModels.Source;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Calls only the official YouTube Data API v3 for user-requested source refreshes. */
@Component
public class YouTubeWatchlistClient {
    private static final String API_ROOT = "https://www.googleapis.com/youtube/v3/";
    private static final Pattern ERROR_REASON = Pattern.compile("\\\"reason\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,80})\\\"");
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final DiscoveryProperties properties;
    private final String apiRoot;

    @Autowired
    public YouTubeWatchlistClient(HttpClient discoveryHttpClient, ObjectMapper mapper, DiscoveryProperties properties) {
        this(discoveryHttpClient, mapper, properties, API_ROOT);
    }

    YouTubeWatchlistClient(HttpClient discoveryHttpClient, ObjectMapper mapper,
            DiscoveryProperties properties, String apiRoot) {
        this.client = discoveryHttpClient;
        this.mapper = mapper;
        this.properties = properties;
        this.apiRoot = apiRoot.endsWith("/") ? apiRoot : apiRoot + "/";
    }

    /** Fetches current source metadata, limited to one API page and the configured video cap. */
    public RefreshResponse refresh(Source source, String apiKey, int maxVideos) throws Exception {
        if ("YOUTUBE_VIDEO".equals(source.sourceType())) return refreshVideo(source, apiKey);
        return refreshChannel(source, apiKey, maxVideos);
    }

    private RefreshResponse refreshVideo(Source source, String key) throws Exception {
        String id = source.externalSourceId();
        VideoResponse response = get("videos?part=snippet,statistics&id=" + encode(id), key, VideoResponse.class);
        Video video = findVideo(response, id);
        if (video == null || video.snippet() == null) throw new IllegalStateException("YouTube video was not found or is unavailable");
        return new RefreshResponse(List.of(toItem(video)), List.of("videos.list"), null,
                "Fetched current metadata for the configured YouTube video.");
    }

    private RefreshResponse refreshChannel(Source source, String key, int maxVideos) throws Exception {
        String selector = source.externalSourceId().startsWith("@")
                ? "forHandle=" + encode(source.externalSourceId().substring(1))
                : "id=" + encode(source.externalSourceId());
        ChannelResponse channels = get("channels?part=snippet,contentDetails&" + selector, key, ChannelResponse.class);
        Channel channel = channels.items() == null || channels.items().isEmpty() ? null : channels.items().getFirst();
        if (channel == null || channel.contentDetails() == null || channel.contentDetails().relatedPlaylists() == null
                || blank(channel.contentDetails().relatedPlaylists().uploads())) {
            throw new IllegalStateException("YouTube channel was not found or has no public uploads playlist");
        }
        int boundedLimit = Math.min(maxVideos, 50);
        PlaylistResponse playlist = get("playlistItems?part=snippet,contentDetails&playlistId="
                + encode(channel.contentDetails().relatedPlaylists().uploads()) + "&maxResults=" + boundedLimit,
                key, PlaylistResponse.class);
        List<String> ids = playlist.items() == null ? List.of() : playlist.items().stream()
                .map(item -> item.contentDetails() == null ? null : item.contentDetails().videoId())
                .filter(id -> !blank(id)).distinct().limit(boundedLimit).toList();
        if (ids.isEmpty()) {
            return new RefreshResponse(List.of(), List.of("channels.list", "playlistItems.list"), channel.id(),
                    "Channel was checked; no public uploads were returned in the bounded page.");
        }
        VideoResponse videos = get("videos?part=snippet,statistics&id=" + encode(String.join(",", ids)),
                key, VideoResponse.class);
        Map<String, Video> videosById = new HashMap<>();
        if (videos.items() != null) videos.items().forEach(video -> videosById.put(video.id(), video));
        List<RefreshItem> items = new ArrayList<>();
        for (String id : ids) {
            Video video = videosById.get(id);
            if (video != null && video.snippet() != null) items.add(toItem(video));
        }
        return new RefreshResponse(items, List.of("channels.list", "playlistItems.list", "videos.list"),
                channel.id(), "Fetched current metadata for the bounded page of channel uploads.");
    }

    private <T> T get(String pathAndQuery, String key, Class<T> type) throws Exception {
        String separator = pathAndQuery.contains("?") ? "&" : "?";
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiRoot + pathAndQuery + separator + "key=" + encode(key)))
                .timeout(properties.requestTimeout()).header("Accept", "application/json").GET().build();
        HttpResponse<byte[]> response = client.send(request,
                com.revenugenereate.discovery.RemoteHttpSafety.boundedBodyHandler(properties.maxFeedBytes()));
        if (response.statusCode() != 200) {
            String body = new String(response.body(), StandardCharsets.UTF_8);
            Matcher reason = ERROR_REASON.matcher(body);
            String apiReason = reason.find() ? "; API reason " + reason.group(1) : "";
            throw new IllegalStateException("YouTube Data API request failed with HTTP "
                    + response.statusCode() + apiReason);
        }
        try {
            return mapper.readValue(response.body(), type);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("YouTube Data API returned an invalid JSON response", exception);
        }
    }

    private static RefreshItem toItem(Video video) {
        Snippet snippet = video.snippet();
        Map<String, Long> statistics = new HashMap<>();
        if (video.statistics() != null) {
            put(statistics, "viewCount", video.statistics().viewCount());
            put(statistics, "likeCount", video.statistics().likeCount());
            put(statistics, "commentCount", video.statistics().commentCount());
        }
        return new RefreshItem(video.id(), snippet.title(), "https://www.youtube.com/watch?v=" + video.id(),
                snippet.publishedAt(), snippet.channelId(), snippet.channelTitle(), Map.copyOf(statistics));
    }

    private static Video findVideo(VideoResponse response, String id) {
        if (response.items() == null) return null;
        return response.items().stream().filter(item -> id.equals(item.id())).findFirst().orElse(null);
    }

    private static void put(Map<String, Long> map, String key, Long value) { if (value != null) map.put(key, value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    /** Result of one API refresh, including safe method names rather than credential-bearing request URLs. */
    public record RefreshResponse(List<RefreshItem> items, List<String> requestReferences,
            String resolvedSourceId, String message) {}

    private record ChannelResponse(List<Channel> items) {}
    private record Channel(String id, ChannelContentDetails contentDetails) {}
    private record ChannelContentDetails(RelatedPlaylists relatedPlaylists) {}
    private record RelatedPlaylists(String uploads) {}
    private record PlaylistResponse(List<PlaylistItem> items) {}
    private record PlaylistItem(PlaylistSnippet snippet, PlaylistContentDetails contentDetails) {}
    private record PlaylistSnippet(PlaylistResourceId resourceId) {}
    private record PlaylistContentDetails(String videoId) {}
    private record PlaylistResourceId(String videoId) {}
    private record VideoResponse(List<Video> items) {}
    private record Video(String id, Snippet snippet, Statistics statistics) {}
    private record Snippet(String title, Instant publishedAt, String channelId, String channelTitle) {}
    private record Statistics(Long viewCount, Long likeCount, Long commentCount) {}
}
