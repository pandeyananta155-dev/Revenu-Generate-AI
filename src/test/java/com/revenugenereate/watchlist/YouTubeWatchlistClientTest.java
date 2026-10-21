package com.revenugenereate.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revenugenereate.discovery.DiscoveryProperties;
import com.revenugenereate.watchlist.WatchlistModels.Source;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "app.data-directory=target/test-data-youtube-client-${random.uuid}")
class YouTubeWatchlistClientTest {
    @Autowired private ObjectMapper mapper;
    @Autowired private DiscoveryProperties properties;
    private HttpServer server;
    private AtomicReference<String> payload;
    private AtomicReference<Integer> status;
    private YouTubeWatchlistClient client;

    @BeforeEach
    void startMockApi() throws Exception {
        payload = new AtomicReference<>();
        status = new AtomicReference<>(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/youtube/v3/videos", exchange -> {
            byte[] body = payload.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/youtube/v3/channels", exchange -> respond(exchange, """
                {"items":[{"id":"UC1234567890123456789012","contentDetails":{"relatedPlaylists":{"uploads":"UPLOADS123"}}}]}
                """));
        server.createContext("/youtube/v3/playlistItems", exchange -> respond(exchange, """
                {"items":[{"contentDetails":{"videoId":"dQw4w9WgXcQ"}}]}
                """));
        server.start();
        client = new YouTubeWatchlistClient(HttpClient.newHttpClient(), mapper, properties,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/youtube/v3/");
    }

    @AfterEach
    void stopMockApi() { if (server != null) server.stop(0); }

    @Test
    void mapsOfficialVideoResponseAndRetainsNativeStatisticsSeparately() throws Exception {
        payload.set("""
                {"items":[{"id":"dQw4w9WgXcQ","snippet":{"title":"A video","publishedAt":"2026-10-20T10:00:00Z","channelId":"UC1234567890123456789012","channelTitle":"A channel"},"statistics":{"viewCount":"42","likeCount":"3"}}]}
                """);
        Source source = source("YOUTUBE_VIDEO", "dQw4w9WgXcQ");

        var result = client.refresh(source, "test-secret", 25);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().title()).isEqualTo("A video");
        assertThat(result.items().getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-20T10:00:00Z"));
        assertThat(result.items().getFirst().nativeStatistics()).containsEntry("viewCount", 42L)
                .containsEntry("likeCount", 3L);
        assertThat(result.requestReferences()).containsExactly("videos.list");
        assertThat(result.toString()).doesNotContain("test-secret");
    }

    @Test
    void resolvesChannelAndReadsOnlyOneBoundedUploadsPage() throws Exception {
        payload.set("""
                {"items":[{"id":"dQw4w9WgXcQ","snippet":{"title":"A channel upload","publishedAt":"2026-10-20T10:00:00Z","channelId":"UC1234567890123456789012","channelTitle":"Channel"},"statistics":{"viewCount":"12"}}]}
                """);
        Source source = source("YOUTUBE_CHANNEL", "@examplehandle");

        var result = client.refresh(source, "test-secret", 25);

        assertThat(result.resolvedSourceId()).isEqualTo("UC1234567890123456789012");
        assertThat(result.items()).hasSize(1);
        assertThat(result.requestReferences()).containsExactly("channels.list", "playlistItems.list", "videos.list");
    }

    @Test
    void surfacesQuotaFailureReasonWithoutEchoingCredentials() {
        status.set(403);
        payload.set("""
                {"error":{"errors":[{"reason":"quotaExceeded"}]}}
                """);

        assertThatThrownBy(() -> client.refresh(source("YOUTUBE_VIDEO", "dQw4w9WgXcQ"), "test-secret", 25))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quotaExceeded")
                .hasMessageNotContaining("test-secret");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private static Source source(String type, String externalId) {
        Instant now = Instant.now();
        String url = "YOUTUBE_VIDEO".equals(type)
                ? "https://www.youtube.com/watch?v=" + externalId
                : "https://www.youtube.com/" + externalId;
        return new Source("a43f30e7-b520-4f40-9935-d3ded2749aae", type, url, externalId,
                "A source", "US", "TECH_GENERAL", List.of(), 3, true, 24, "PENDING",
                null, null, null, now, now, null);
    }
}
