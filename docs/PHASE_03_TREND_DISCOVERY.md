# Phase 3: Trend Discovery

Phase 3 adds provider discovery and evidence persistence only. It does not
implement watchlist CRUD, monitoring schedules, scoring, content generation,
video rendering, review, monetization, publishing, or background scheduling.
No composite cross-provider score is calculated.

## API routes

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/discovery/providers` | Report availability, configuration, and policy blocks. |
| `POST` | `/api/v1/discovery/scans` | Run selected or currently enabled providers for one market/niche. |
| `GET` | `/api/v1/discovery/scans/{scanId}` | Read the persisted scan status and provider summaries. |
| `GET` | `/api/v1/discovery/scans/{scanId}/results` | Filter and paginate attributed evidence. |
| `POST` | `/api/v1/discovery/google-trends/import` | Import a bounded, user-provided Google Trends CSV. |

Create a scan:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/discovery/scans \
  -H 'Content-Type: application/json' \
  -d '{
    "marketCode": "US",
    "nicheCode": "AI_DEV_TOOLS",
    "windowDays": 7,
    "providers": ["GDELT_NEWS"],
    "maxResults": 25
  }'
```

`providers` is optional; when absent or empty, only enabled providers are run.
The allowed window is 1–30 days. `maxResults` is capped by
`discovery.max-results-per-scan`. Provider failures are isolated and summarized
as `FAILED`; successful evidence from other providers is retained and the scan
is `PARTIAL`. A scan with no retained evidence is `NO_RESULTS`, including when
all enabled sources return no items. Timestamped provider items older than the
requested window are omitted; items without a source timestamp are retained
without fabricating one. Provider status and result status are explicit.

List results, optionally filtering by provider code, ISO country, and niche:

```bash
curl -s 'http://127.0.0.1:8080/api/v1/discovery/scans/SCAN_ID/results?provider=GDELT_NEWS&country=US&niche=AI_DEV_TOOLS&page=0&size=25'
```

The response includes a stable page envelope. `countryCode` and
`countrySemantics` are both returned: target-market context is not silently
presented as verified audience geography. Results are marked `UNREVIEWED`.

## Providers

### RSS and Atom

Only endpoints saved in an enabled source profile of type `RSS_ATOM` or
`GOOGLE_TRENDS` are fetched. Configure and review each feed through the existing
provider API, for example:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/providers \
  -H 'Content-Type: application/json' \
  -d '{
    "code":"MY_APPROVED_FEED",
    "name":"Approved publisher feed",
    "sourceType":"RSS_ATOM",
    "endpointUrls":["https://publisher.example/feed.xml"],
    "enabled":true,
    "permissions":{"display":true,"scoring":false,"contentGrounding":false,"mediaDownload":false},
    "termsUrl":"https://publisher.example/terms",
    "termsReviewDate":"2026-10-21",
    "retentionDays":30,
    "requiredApprovals":[],
    "secretEnvironmentVariables":[]
  }'
```

The adapter parses RSS and Atom with external XML entities and DTD processing
disabled. DNS results are checked to reject loopback, private, link-local, and
multicast destinations. Redirects are disabled in the HTTP client, limited to
two, and every new target is revalidated. Private feed URLs can be enabled only
for local development using
`discovery.rss.allow-private-addresses-for-development`; leave this `false` in
normal use. Feed responses have a byte cap and timeout. An in-memory cache
honors a configurable refresh interval and conditional `ETag` /
`Last-Modified` responses. This cache is not shared across app restarts.

Feed entries and dates are publisher supplied and not independently verified.
The app stores titles, short excerpts, links, provider attribution, and
configured retention deadlines; it does not download linked media or crawl
article pages.

### GDELT DOC API

GDELT is disabled by default. Set `discovery.gdelt.enabled: true` and enable
the `GDELT_NEWS` source profile. The adapter uses the documented DOC API
article-list mode (`mode=artlist`, JSON response, bounded `maxrecords`, and
start/end datetimes) at the configured HTTPS endpoint:

```yaml
discovery:
  gdelt:
    enabled: true
    base-url: https://api.gdeltproject.org/api/v2/doc/doc
    request-interval: 15s
```

The query uses configured niche keywords and a GDELT `sourcecountry` filter.
This describes publisher/source metadata; it is not proof of readership in the
requested target market. GDELT news coverage is not social engagement, search
volume, or demand. Each record carries that limitation and its article URL.
Provider calls are rate-limited and independently bounded by timeout and
response size. See the [GDELT DOC API reference](https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/).

### Google Trends CSV

The app does not scrape Google Trends. Export data yourself and import it with
explicit country and niche values:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/discovery/google-trends/import \
  -F 'file=@trends.csv;type=text/csv' \
  -F 'countryCode=US' \
  -F 'nicheCode=AI_DEV_TOOLS'
```

CSV headers must include a query/search-term column (`Query`, `Search term`,
`Term`, or `Keyword`) and an interest/value column (`Interest`, `Search
interest`, `Value`, or `Relative interest`). An optional `Date`, `Time`,
`Period`, or `Time period` column is retained as the export window. Interest
values are stored as provider-native text and must be in the 0–100 range or
`<1`; they are not an absolute search-volume measure. The import records source
attribution, filename, country, niche, time window, row count, and import time.
It does not evaluate formulas; unsupported values are rejected.

Limits are 1 MiB per upload and 1,000 data rows. Empty files, missing required
headers, unsupported extensions, invalid context, oversized cells, and
malformed CSV return an RFC 9457 error response. The uploaded file itself is
not stored. A successful response contains the resulting `scanId`; its rows
are available from the scan results route. See
[Google Trends data and attribution](https://support.google.com/trends/answer/4365533).

### YouTube Data API

YouTube discovery is optional and disabled by default. To enable it, configure
`discovery.youtube.enabled: true`, enable the `YOUTUBE_WATCHLIST` source
profile, and provide the API key only through its configured environment
variable:

```bash
export YOUTUBE_API_KEY='your-key'
```

The application never returns the key and does not log full request URLs. The
adapter uses the official YouTube Data API v3 `search.list` and `videos.list`
resources. It performs no more than two API calls per scan and reserves 101
quota units per scan (100 for search and 1 for video statistics) against
`discovery.youtube.daily-quota-unit-budget`. Usage is tracked in SQLite by UTC
date. Search region is context, not verified viewer geography. Views, likes,
and comments are returned only as separate platform-native counts; Phase 3
does not combine, rank, or use them for scoring or content grounding. API key
and policy details are in the [YouTube API Services terms](https://developers.google.com/youtube/terms/api-services-terms-of-service).

### Reddit

Reddit is always reported as `BLOCKED`, and the application makes no Reddit
network calls. A source-profile toggle alone cannot enable it. Any future
commercial integration requires documented written permission, approved
credentials, and a separate implementation review against current Reddit
terms.

## Configuration, persistence, and retention

Provider limits and switches live under `discovery.*` in
`src/main/resources/application.yml`. Niche keywords live in
`discovery.niche-keywords`; an empty/missing niche entry uses
`discovery.default-keywords`. HTTP request timeout, maximum response bytes,
RSS refresh interval, GDELT request interval, CSV size, per-scan results, and
provider-specific quota/retention are configurable there.

SQLite schema version 2 adds `discovery_scans`, `discovery_results`,
`google_trends_imports`, and `youtube_quota_usage`; version 1 tables and rows
are preserved. Evidence records include provider, query terms, source URL,
publication/fetch timestamps, country semantics, provider-native values,
limitations, review state, and `delete_after`. Expired evidence is deleted on
the next scan or result read, rather than by a background scheduler. Import
audit metadata and scan summaries are retained separately. Keep the local
database and backups private.

## Known limitations and troubleshooting

- `DISABLED` or `UNCONFIGURED` means no network request was made. Enable the
  documented source switch/profile or supply the required environment key.
- `BLOCKED` for Reddit is intentional and cannot be bypassed via API input.
- A `PARTIAL` scan indicates at least one provider failed; read each provider
  summary. Other provider results remain available.
- RSS feeds that redirect to a private or disallowed network address are
  rejected. Use the publisher's public HTTPS feed URL; do not allow private
  addresses outside local development.
- An RSS cache is process-local. Restarting the app clears validators and
  cached items.
- GDELT reports publisher/source geography and news coverage, not audience
  geography or popularity.
- Google Trends relative interest is normalized and must not be interpreted as
  absolute search volume.
- YouTube quota exhaustion is returned in the provider summary; raise the
  configured cap only after checking the current official API quota allocation.
- Check application logs for provider code and failure class. Full URLs, API
  keys, and provider response bodies are not returned in error messages.

