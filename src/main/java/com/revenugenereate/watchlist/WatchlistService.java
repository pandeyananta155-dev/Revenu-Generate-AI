package com.revenugenereate.watchlist;

import com.revenugenereate.api.ApiRequestException;
import com.revenugenereate.api.WatchlistRequests.CreateNote;
import com.revenugenereate.api.WatchlistRequests.CreateSource;
import com.revenugenereate.api.WatchlistRequests.PatchNote;
import com.revenugenereate.api.WatchlistRequests.PatchSource;
import com.revenugenereate.config.AppProperties;
import com.revenugenereate.config.CatalogProperties;
import com.revenugenereate.config.HttpUrlValidator;
import com.revenugenereate.discovery.DiscoveryProperties;
import com.revenugenereate.discovery.DiscoveryRepository;
import com.revenugenereate.discovery.YouTubeDiscoveryProvider;
import com.revenugenereate.watchlist.WatchlistModels.Page;
import com.revenugenereate.watchlist.WatchlistModels.ProviderReadiness;
import com.revenugenereate.watchlist.WatchlistModels.RefreshHistory;
import com.revenugenereate.watchlist.WatchlistModels.ResearchNote;
import com.revenugenereate.watchlist.WatchlistModels.Source;
import com.revenugenereate.watchlist.YouTubeWatchlistClient.RefreshResponse;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Validates watchlist configuration, runs explicit provider refreshes, and manages personal research notes. */
@Service
public class WatchlistService {
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final int MAX_PAGE_SIZE = 100;
    private final List<CatalogProperties.Market> markets;
    private final List<CatalogProperties.Niche> niches;
    private final WatchlistRepository repository;
    private final WatchlistProperties properties;
    private final DiscoveryProperties discoveryProperties;
    private final DiscoveryRepository discoveryRepository;
    private final YouTubeDiscoveryProvider readinessProvider;
    private final YouTubeWatchlistClient youtubeClient;
    private final java.util.Set<String> refreshing = ConcurrentHashMap.newKeySet();

    public WatchlistService(AppProperties appProperties, WatchlistRepository repository,
            WatchlistProperties properties, DiscoveryProperties discoveryProperties,
            DiscoveryRepository discoveryRepository, YouTubeDiscoveryProvider readinessProvider,
            YouTubeWatchlistClient youtubeClient) {
        this.markets = List.copyOf(appProperties.catalogs().markets());
        this.niches = List.copyOf(appProperties.catalogs().niches());
        this.repository = repository;
        this.properties = properties;
        this.discoveryProperties = discoveryProperties;
        this.discoveryRepository = discoveryRepository;
        this.readinessProvider = readinessProvider;
        this.youtubeClient = youtubeClient;
    }

    /** Lists sources by page, excluding archived sources unless explicitly requested. */
    public Page<Source> listSources(boolean includeArchived, int page, int size) {
        validatePage(page, size);
        return new Page<>(repository.findSources(includeArchived, size, offset(page, size)),
                page, size, repository.countSources(includeArchived));
    }

    /** Returns one source, including archived entries so their history remains addressable. */
    public Source getSource(String id) {
        return repository.findSource(validateId(id))
                .orElseThrow(() -> notFound("Watchlist source", id));
    }

    /** Adds a channel or video URL without making a network request. */
    public Source createSource(CreateSource request) {
        YouTubeSourceUrl.ParsedSource parsed = parseSource(request.sourceType(), request.sourceUrl());
        validateContext(request.countryCode(), request.nicheCode());
        Instant now = Instant.now();
        Source source = new Source(UUID.randomUUID().toString(), parsed.sourceType(), parsed.sourceUrl(),
                parsed.externalSourceId(), clean(request.displayName()), request.countryCode(), request.nicheCode(),
                normalizeStrings(request.keywords()), valueOr(request.priority(), 3),
                request.enabled() == null || request.enabled(), valueOr(request.refreshIntervalHours(), 24),
                request.enabled() != null && !request.enabled() ? "DISABLED" : "PENDING",
                null, null, null, now, now, null);
        if (repository.existsDuplicate(null, source.sourceType(), source.sourceUrl(),
                source.countryCode(), source.nicheCode())) {
            throw conflict("A watchlist entry already exists for this YouTube source, market, and niche");
        }
        try {
            repository.insert(source);
        } catch (DataAccessException exception) {
            if (isUniqueConstraint(exception)) {
                throw conflict("A watchlist entry already exists for this YouTube source, market, and niche");
            }
            throw exception;
        }
        return getSource(source.id());
    }

    /** Seeds optional YAML-defined sources once; database-managed configuration wins on later startups. */
    public void seedConfiguredSources() {
        if (properties.sources() == null) return;
        for (WatchlistProperties.SourceDefinition definition : properties.sources()) {
            YouTubeSourceUrl.ParsedSource parsed = parseSource(definition.sourceType(), definition.sourceUrl());
            validateContext(definition.countryCode(), definition.nicheCode());
            if (repository.existsDuplicate(null, parsed.sourceType(), parsed.sourceUrl(),
                    definition.countryCode(), definition.nicheCode())) continue;
            createSource(new CreateSource(definition.sourceType(), definition.sourceUrl(), definition.displayName(),
                    definition.countryCode(), definition.nicheCode(), definition.keywords(), definition.priority(),
                    definition.enabled(), definition.refreshIntervalHours()));
        }
    }

    /** Removes expired API data at startup so an offline period cannot leave it exposed after restart. */
    public void pruneExpiredHistory() { repository.pruneExpiredHistory(); }

    /** Applies a partial update while retaining the source identity and check history. */
    public Source updateSource(String id, PatchSource patch) {
        Source current = getSource(id);
        if (current.archivedAt() != null) throw conflict("Archived sources cannot be edited");
        String type = choose(patch.sourceType(), current.sourceType());
        String url = choose(patch.sourceUrl(), current.sourceUrl());
        YouTubeSourceUrl.ParsedSource parsed = parseSource(type, url);
        String country = choose(patch.countryCode(), current.countryCode());
        String niche = choose(patch.nicheCode(), current.nicheCode());
        validateContext(country, niche);
        boolean changedIdentity = !type.equals(current.sourceType()) || !parsed.sourceUrl().equals(current.sourceUrl());
        boolean enabled = patch.enabled() == null ? current.enabled() : patch.enabled();
        String displayName = choose(patch.displayName(), current.displayName()).trim();
        if (displayName.isBlank()) throw badRequest("displayName must not be blank");
        boolean enablementChanged = enabled != current.enabled();
        String status = !enabled ? "DISABLED"
                : changedIdentity || enablementChanged ? "PENDING" : current.sourceStatus();
        Source updated = new Source(current.id(), type, parsed.sourceUrl(), parsed.externalSourceId(),
                displayName, country, niche,
                patch.keywords() == null ? current.keywords() : normalizeStrings(patch.keywords()),
                valueOr(patch.priority(), current.priority()), enabled,
                valueOr(patch.refreshIntervalHours(), current.refreshIntervalHours()), status,
                current.lastCheckedAt(), current.lastSuccessfulCheckAt(), changedIdentity ? null : current.lastError(),
                current.createdAt(), Instant.now(), null);
        if (repository.existsDuplicate(id, updated.sourceType(), updated.sourceUrl(),
                updated.countryCode(), updated.nicheCode())) {
            throw conflict("A watchlist entry already exists for this YouTube source, market, and niche");
        }
        try {
            repository.update(updated);
        } catch (DataAccessException exception) {
            if (isUniqueConstraint(exception)) {
                throw conflict("A watchlist entry already exists for this YouTube source, market, and niche");
            }
            throw exception;
        }
        return getSource(id);
    }

    /** Enables or disables a source while retaining all existing refresh history. */
    public Source setEnabled(String id, boolean enabled) {
        Source current = getSource(id);
        if (current.archivedAt() != null) throw conflict("Archived sources cannot be enabled");
        Source updated = new Source(current.id(), current.sourceType(), current.sourceUrl(),
                current.externalSourceId(), current.displayName(), current.countryCode(), current.nicheCode(),
                current.keywords(), current.priority(), enabled, current.refreshIntervalHours(),
                enabled ? "PENDING" : "DISABLED", current.lastCheckedAt(), current.lastSuccessfulCheckAt(),
                current.lastError(), current.createdAt(), Instant.now(), null);
        repository.update(updated);
        return getSource(id);
    }

    /** Archives a source instead of deleting it, preserving linked notes and refresh history. */
    public Source archiveSource(String id) {
        Source current = getSource(id);
        if (current.archivedAt() == null) repository.archive(id, Instant.now());
        return getSource(id);
    }

    /** Reports YouTube API readiness without returning credentials or request URLs. */
    public ProviderReadiness providerReadiness() {
        var status = readinessProvider.status();
        return new ProviderReadiness(status.code(), status.status(), status.reason());
    }

    /** Performs one bounded manual refresh and stores only short-lived provider data plus compact history. */
    public RefreshHistory refresh(String id) {
        Source source = getSource(id);
        if (source.archivedAt() != null) throw conflict("Archived sources cannot be refreshed");
        if (!source.enabled()) throw conflict("Disabled sources are not refreshed; enable the source first");
        if (!refreshing.add(id)) throw conflict("A refresh is already in progress for this source");
        try {
            ProviderReadiness readiness = providerReadiness();
            if (!"AVAILABLE".equals(readiness.status())) {
                throw new ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "youtube-provider-unavailable",
                        "YouTube refresh is unavailable: " + readiness.reason());
            }
            String key = System.getenv(discoveryProperties.youtube().apiKeyEnvironmentVariable());
            int quotaUnits = "YOUTUBE_VIDEO".equals(source.sourceType()) ? 1 : 3;
            if (!discoveryRepository.reserveYoutubeQuota(quotaUnits,
                    discoveryProperties.youtube().dailyQuotaUnitBudget())) {
                return saveFailure(source, new IllegalStateException("Daily YouTube API quota budget exhausted"));
            }
            long started = System.nanoTime();
            try {
                RefreshResponse response = youtubeClient.refresh(source, key, properties.maxVideosPerRefresh());
                Instant checkedAt = Instant.now();
                Instant deleteAfter = checkedAt.plus(Math.min(30, properties.historyRetentionDays()), ChronoUnit.DAYS);
                Source resolved = withExternalId(source, response.resolvedSourceId());
                repository.recordCheck(resolved, checkedAt, true, null, response.resolvedSourceId());
                RefreshHistory history = new RefreshHistory(UUID.randomUUID().toString(), id, checkedAt,
                        "SUCCEEDED", response.items().size(), response.message(), response.requestReferences(),
                        response.items(), deleteAfter);
                repository.saveHistory(history);
                return history;
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                return saveFailure(source, exception);
            }
        } finally {
            refreshing.remove(id);
        }
    }

    /** Returns retained refresh history for one source. */
    public Page<RefreshHistory> history(String id, int page, int size) {
        getSource(id);
        validatePage(page, size);
        List<RefreshHistory> items = repository.findHistory(id, size, offset(page, size));
        // History is deliberately capped at 100 rows per source, so this is a bounded count.
        long total = repository.findHistory(id, 100, 0).size();
        return new Page<>(items, page, size, total);
    }

    /** Lists personal notes, optionally scoped to a watched source. */
    public Page<ResearchNote> listNotes(String sourceId, int page, int size) {
        if (sourceId != null) getSource(sourceId);
        validatePage(page, size);
        return new Page<>(repository.findNotes(sourceId, size, offset(page, size)), page, size,
                repository.countNotes(sourceId));
    }

    /** Creates a note and validates its optional source, market, niche, and reference URL. */
    public ResearchNote createNote(CreateNote request) {
        validateContext(request.countryCode(), request.nicheCode());
        if (request.sourceId() != null) getSource(request.sourceId());
        validateNoteUrl(request.relatedSourceUrl());
        Instant now = Instant.now();
        ResearchNote note = new ResearchNote(UUID.randomUUID().toString(), request.sourceId(),
                request.title().trim(), request.noteText().trim(), request.relatedSourceUrl(), request.countryCode(),
                request.nicheCode(), normalizeStrings(request.tags()), now, now);
        repository.insertNote(note);
        return getNote(note.id());
    }

    /** Returns one research note. */
    public ResearchNote getNote(String id) {
        return repository.findNote(validateId(id)).orElseThrow(() -> notFound("Research note", id));
    }

    /** Updates a note without treating its user-authored text as verified source evidence. */
    public ResearchNote updateNote(String id, PatchNote patch) {
        ResearchNote current = getNote(id);
        String sourceId = choose(patch.sourceId(), current.sourceId());
        if (sourceId != null) getSource(sourceId);
        String title = choose(patch.title(), current.title());
        String body = choose(patch.noteText(), current.noteText());
        if (title.isBlank() || body.isBlank()) throw badRequest("Note title and noteText must not be blank");
        String url = choose(patch.relatedSourceUrl(), current.relatedSourceUrl());
        validateNoteUrl(url);
        String country = choose(patch.countryCode(), current.countryCode());
        String niche = choose(patch.nicheCode(), current.nicheCode());
        validateContext(country, niche);
        ResearchNote updated = new ResearchNote(id, sourceId, title.trim(), body.trim(), url, country, niche,
                patch.tags() == null ? current.tags() : normalizeStrings(patch.tags()),
                current.createdAt(), Instant.now());
        repository.updateNote(updated);
        return getNote(id);
    }

    /** Deletes a personal note; the associated watchlist source remains untouched. */
    public void deleteNote(String id) {
        getNote(id);
        repository.deleteNote(id);
    }

    private RefreshHistory saveFailure(Source source, Exception exception) {
        Instant checkedAt = Instant.now();
        String message = safeFailureMessage(exception);
        repository.recordCheck(source, checkedAt, false, message, null);
        RefreshHistory history = new RefreshHistory(UUID.randomUUID().toString(), source.id(), checkedAt,
                "FAILED", 0, message, List.of(), List.of(),
                checkedAt.plus(Math.min(30, properties.historyRetentionDays()), ChronoUnit.DAYS));
        repository.saveHistory(history);
        return history;
    }

    private static String safeFailureMessage(Exception exception) {
        if (exception instanceof HttpTimeoutException) return "YouTube API request timed out";
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return "YouTube API request failed (" + exception.getClass().getSimpleName() + ")";
        message = message.replaceAll("(?i)(key=)[^&\\s]+", "$1[redacted]").replaceAll("[\\r\\n\\t]", " ").trim();
        if (message.length() > 240) message = message.substring(0, 240);
        return message;
    }

    private YouTubeSourceUrl.ParsedSource parseSource(String type, String url) {
        try { return YouTubeSourceUrl.parse(type, url); }
        catch (IllegalArgumentException exception) { throw badRequest(exception.getMessage()); }
    }

    private void validateContext(String countryCode, String nicheCode) {
        if (countryCode != null && (!List.of(Locale.getISOCountries()).contains(countryCode)
                || markets.stream().noneMatch(market -> market.countryCode().equals(countryCode)))) {
            throw badRequest("countryCode must match a configured market country");
        }
        if (nicheCode != null && niches.stream().noneMatch(niche -> niche.code().equals(nicheCode))) {
            throw badRequest("nicheCode must match a configured niche");
        }
    }

    private static void validateNoteUrl(String url) {
        if (url != null && !url.isBlank() && !HttpUrlValidator.isSafeHttpUrl(url)) {
            throw badRequest("relatedSourceUrl must be a valid HTTP or HTTPS URL without credentials");
        }
    }

    private static List<String> normalizeStrings(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    private static Source withExternalId(Source source, String externalId) {
        if (externalId == null || externalId.isBlank() || externalId.equals(source.externalSourceId())) return source;
        return new Source(source.id(), source.sourceType(), source.sourceUrl(), externalId, source.displayName(),
                source.countryCode(), source.nicheCode(), source.keywords(), source.priority(), source.enabled(),
                source.refreshIntervalHours(), source.sourceStatus(), source.lastCheckedAt(), source.lastSuccessfulCheckAt(),
                source.lastError(), source.createdAt(), source.updatedAt(), source.archivedAt());
    }

    private static String validateId(String id) {
        if (id == null || !UUID_PATTERN.matcher(id).matches()) throw badRequest("Identifier must be a UUID");
        return id;
    }

    private static boolean isUniqueConstraint(DataAccessException exception) {
        Throwable root = exception.getMostSpecificCause();
        return root instanceof java.sql.SQLException sqlException
                && sqlException.getErrorCode() == 19
                && sqlException.getMessage() != null
                && sqlException.getMessage().toUpperCase(Locale.ROOT).contains("UNIQUE");
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw badRequest("page must be non-negative and size must be 1–100");
    }

    private static int offset(int page, int size) {
        try { return Math.multiplyExact(page, size); }
        catch (ArithmeticException exception) { throw badRequest("page is too large"); }
    }

    private static int valueOr(Integer value, int fallback) { return value == null ? fallback : value; }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static <T> T choose(T replacement, T current) { return replacement == null ? current : replacement; }
    private static ApiRequestException notFound(String type, String id) {
        return new ApiRequestException(HttpStatus.NOT_FOUND, "not-found", type + " was not found");
    }
    private static ApiRequestException badRequest(String message) {
        return new ApiRequestException(HttpStatus.BAD_REQUEST, "invalid-watchlist-request", message);
    }
    private static ApiRequestException conflict(String message) {
        return new ApiRequestException(HttpStatus.CONFLICT, "watchlist-conflict", message);
    }
}
