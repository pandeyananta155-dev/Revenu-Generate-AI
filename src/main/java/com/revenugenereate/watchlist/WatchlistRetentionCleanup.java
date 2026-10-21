package com.revenugenereate.watchlist;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically removes expired YouTube API data from local refresh history. */
@Component
public class WatchlistRetentionCleanup {
    private final WatchlistRepository repository;

    public WatchlistRetentionCleanup(WatchlistRepository repository) { this.repository = repository; }

    /** Applies the 30-day maximum retention deadline without starting any provider refreshes. */
    @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
    public void removeExpiredHistory() { repository.pruneExpiredHistory(); }
}
