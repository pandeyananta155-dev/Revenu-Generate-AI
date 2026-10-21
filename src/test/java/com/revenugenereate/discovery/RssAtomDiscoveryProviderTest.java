package com.revenugenereate.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.revenugenereate.catalog.SourceProfile;
import com.revenugenereate.config.CatalogProperties;
import com.revenugenereate.discovery.DiscoveryProvider.ProviderContext;
import com.revenugenereate.persistence.CatalogRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RssAtomDiscoveryProviderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesAtomAndUsesConditionalRequestsForConfiguredFeeds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/feed", exchange -> {
            requests.incrementAndGet();
            if ("\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.getResponseHeaders().add("ETag", "\"v1\"");
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] xml = """
                    <?xml version="1.0"?>
                    <feed xmlns="http://www.w3.org/2005/Atom">
                      <entry><id>https://publisher.example/story/1</id><title>Safe &amp; useful trend</title>
                      <summary>Short excerpt</summary><published>2026-10-20T10:00:00Z</published>
                      <link href="https://publisher.example/story/1"/></entry>
                    </feed>
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            exchange.sendResponseHeaders(200, xml.length);
            exchange.getResponseBody().write(xml);
            exchange.close();
        });
        server.start();
        String feedUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/feed";
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findAllProviders()).thenReturn(List.of(profile(feedUrl)));
        DiscoveryProperties properties = properties(Duration.ZERO, true);
        RssAtomDiscoveryProvider provider = new RssAtomDiscoveryProvider(
                catalog, properties, newHttpClient(properties));
        ProviderContext context = new ProviderContext(
                "US", "US", "AI_DEV_TOOLS", List.of("AI tools"), Instant.now().minusSeconds(3600), 10, "TEST_FEED");

        var first = provider.discover(context);
        Thread.sleep(10);
        var second = provider.discover(context);

        assertThat(first).hasSize(1);
        assertThat(first.getFirst().title()).isEqualTo("Safe & useful trend");
        assertThat(first.getFirst().sourceName()).isEqualTo("Configured feed");
        assertThat(first.getFirst().publishedAt()).isEqualTo(Instant.parse("2026-10-20T10:00:00Z"));
        assertThat(second).hasSize(1);
        assertThat(requests).hasValue(2);
    }

    @Test
    void rejectsPrivateFeedDestinationsWhenDevelopmentOverrideIsOff() throws Exception {
        String feedUrl = "http://127.0.0.1:8080/feed";
        CatalogRepository catalog = mock(CatalogRepository.class);
        when(catalog.findAllProviders()).thenReturn(List.of(profile(feedUrl)));
        DiscoveryProperties properties = properties(Duration.ZERO, false);
        RssAtomDiscoveryProvider provider = new RssAtomDiscoveryProvider(
                catalog, properties, newHttpClient(properties));

        assertThatThrownBy(() -> provider.discover(new ProviderContext(
                "US", "US", "AI_DEV_TOOLS", List.of("AI tools"), Instant.now(), 5, "TEST_FEED")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("restricted network address");
    }

    private static DiscoveryProperties properties(Duration refresh, boolean allowPrivate) {
        return new DiscoveryProperties(20, 1048576, 1048576, Duration.ofSeconds(2), refresh,
                List.of("technology"), java.util.Map.of(), new DiscoveryProperties.Gdelt(false,
                "https://api.gdeltproject.org/api/v2/doc/doc", Duration.ZERO),
                new DiscoveryProperties.Youtube(false, "YOUTUBE_API_KEY", 2, 10000, 30),
                new DiscoveryProperties.Rss(allowPrivate));
    }

    private static java.net.http.HttpClient newHttpClient(DiscoveryProperties properties) {
        return java.net.http.HttpClient.newBuilder().connectTimeout(properties.requestTimeout())
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    }

    private static SourceProfile profile(String url) {
        return new SourceProfile("TEST_FEED", "Configured feed", "RSS_ATOM", List.of(url), true,
                new CatalogProperties.Permissions(true, false, false, false), "https://example.com/terms",
                LocalDate.now(), 30, List.of(), List.of(), null, null, null, null);
    }
}
