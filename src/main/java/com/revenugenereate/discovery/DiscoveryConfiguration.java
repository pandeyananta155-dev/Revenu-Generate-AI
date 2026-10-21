package com.revenugenereate.discovery;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.revenugenereate.watchlist.WatchlistProperties;

/** Shared HTTP client and configuration wiring for bounded discovery requests. */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({DiscoveryProperties.class, WatchlistProperties.class})
public class DiscoveryConfiguration {

    /** Uses a single HTTP client and disables automatic redirects so each target can be revalidated. */
    @Bean
    HttpClient discoveryHttpClient(DiscoveryProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
