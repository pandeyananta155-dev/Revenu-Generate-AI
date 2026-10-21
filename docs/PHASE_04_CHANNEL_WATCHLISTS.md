# Phase 4: YouTube watchlists and research notes

Phase 4 adds configurable YouTube channel/video watchlist records, manual
metadata refreshes through the official YouTube Data API v3, bounded refresh
history, and personal notes. It does not score sources, generate scripts,
render videos, approve content, publish, or schedule publishing.

## YouTube provider setup

The application starts when the YouTube API key is absent. Automated calls
require all of the following:

1. Set the API key in the process environment, never in YAML:

   ```bash
   export YOUTUBE_API_KEY='your-key'
   ```

2. Enable the API adapter in `discovery.youtube.enabled` in
   `src/main/resources/application.yml` or a Spring Boot environment override.
3. Enable the existing `YOUTUBE_WATCHLIST` provider profile through
   `PATCH /api/v1/providers/YOUTUBE_WATCHLIST` with `{"enabled":true}`.
4. Configure the source in YAML or create it through the watchlist API.

Check readiness with `GET /api/v1/watchlists/provider-status`. Status is
`AVAILABLE` only when both switches and the environment key are present;
`DISABLED`, `UNCONFIGURED`, and `BLOCKED` include a reason. No response returns
the key value.

The existing `discovery.youtube.daily-quota-unit-budget` is shared with Phase 3
YouTube discovery. A video refresh reserves one quota unit. A channel refresh
reserves three units for channel lookup, one uploads-playlist page, and video
metadata. Failed calls still consume the reserved local budget, matching the
fact that a provider may have charged the request. No retry is made on quota,
permission, malformed-response, or timeout failures.

## Add sources through YAML

Optional entries under `watchlists.sources` are seeded into SQLite at startup.
They are inserted only when the same normalized source/market/niche does not
already exist, so subsequent API edits remain authoritative:

```yaml
watchlists:
  max-videos-per-refresh: 25
  history-retention-days: 30
  sources:
    - source-type: YOUTUBE_CHANNEL
      source-url: https://www.youtube.com/@GoogleDevelopers
      display-name: Google Developers
      country-code: US
      niche-code: TECH_GENERAL
      keywords: [developer tools, software releases]
      priority: 3
      enabled: true
      refresh-interval-hours: 24
    - source-type: YOUTUBE_VIDEO
      source-url: https://youtu.be/dQw4w9WgXcQ
      display-name: Example video
      country-code: NP
      niche-code: NEPAL_DIGITAL
      keywords: [digital services]
      priority: 4
      enabled: false
      refresh-interval-hours: 72
```

`country-code` must be an ISO code present in the configured market catalog;
`niche-code` must exist in the configured niche catalog. The YouTube URL host
and identifier are checked and the URL is stored in canonical form. Supported
channel URL formats are `/channel/UC…` and `/@handle`. Supported video formats
include `watch?v=…`, `youtu.be/…`, `/shorts/…`, `/live/…`, and `/embed/…`.
Other hosts, non-HTTPS URLs, custom channel redirect URLs, malformed IDs, and
credential-bearing query parameters are rejected.

## REST routes

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/watchlists` | Paginated sources; `includeArchived=true` includes archives. |
| `POST` | `/api/v1/watchlists` | Add a channel or video. Does not make an API call. |
| `GET` | `/api/v1/watchlists/{id}` | Get source state. |
| `PATCH` | `/api/v1/watchlists/{id}` | Update configuration. |
| `PATCH` | `/api/v1/watchlists/{id}/enabled` | Enable or disable without deleting history. |
| `DELETE` | `/api/v1/watchlists/{id}` | Soft archive; keeps notes and history. |
| `GET` | `/api/v1/watchlists/provider-status` | Read non-secret provider readiness. |
| `POST` | `/api/v1/watchlists/{id}/refresh` | Manually refresh an enabled source. |
| `GET` | `/api/v1/watchlists/{id}/history` | Paginate retained refresh outcomes. |
| `GET` | `/api/v1/research-notes` | Paginate notes; optional `sourceId` filter. |
| `POST` | `/api/v1/research-notes` | Create a personal note with an optional source link. |
| `GET` | `/api/v1/research-notes/{id}` | Read a note. |
| `PATCH` | `/api/v1/research-notes/{id}` | Update a note. |
| `DELETE` | `/api/v1/research-notes/{id}` | Delete a note. |

Create a channel source and request its first refresh:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/watchlists \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"YOUTUBE_CHANNEL","sourceUrl":"https://www.youtube.com/@GoogleDevelopers","displayName":"Google Developers","countryCode":"US","nicheCode":"TECH_GENERAL","keywords":["developer tools"],"priority":3,"enabled":true,"refreshIntervalHours":24}'

curl -i -X POST http://127.0.0.1:8080/api/v1/watchlists/SOURCE_ID/refresh
```

Create a video source with a Nepal market and add a note:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/watchlists \
  -H 'Content-Type: application/json' \
  -d '{"sourceType":"YOUTUBE_VIDEO","sourceUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","displayName":"Video to review","countryCode":"NP","nicheCode":"NEPAL_DIGITAL","enabled":true}'

curl -i -X POST http://127.0.0.1:8080/api/v1/research-notes \
  -H 'Content-Type: application/json' \
  -d '{"sourceId":"SOURCE_ID","title":"Verify this claim","noteText":"Check the linked release notes before using this detail.","relatedSourceUrl":"https://example.com/release","countryCode":"NP","nicheCode":"NEPAL_DIGITAL","tags":["verification"]}'
```

All paths, request records, and response schemas appear in the existing Swagger
UI at `http://127.0.0.1:8080/swagger-ui.html` and the OpenAPI document at
`http://127.0.0.1:8080/v3/api-docs`.

## Refresh and retention behavior

Refresh is synchronous and manual. A disabled or archived source cannot be
refreshed. `refresh-interval-hours` is stored as the requested cadence for
future automation; this phase does not start an automatic provider-refresh
scheduler. Channel refresh resolves a handle/channel ID, reads one bounded page
from the channel's public uploads playlist, and requests current video metadata
for at most `max-videos-per-refresh` items. Video refresh reads the selected
video directly. Request timeouts, response-size bounds, API errors, and quota
budget are enforced. Failures update `lastCheckedAt` and `lastError` but never
advance `lastSuccessfulCheckAt`.

Refresh history keeps at most 100 attempts per source and expires after the
configured number of days (capped at YouTube's 30-day refresh/deletion window).
Expired rows are cleaned at application startup, when history is accessed, on
new history writes, and daily at 03:15 UTC. No API response is logged. The
database stores only bounded video titles, source URLs, provider timestamps,
channel identifiers, and source-native statistics needed by the refresh
response/history. No descriptions, comments, or media are stored.

Statistics are returned as distinct YouTube-native fields and are not combined
with other platforms or transformed into a score. This implementation does
not use the optional approved-derived-metrics exception. Refresh history is
local to this application; protect the SQLite file and backups.

Research notes are user-authored and are not verified claims. Their URLs are
stored for attribution but never fetched. A source archive disables refreshes
while preserving linked notes and history. Notes can be deleted independently.

## Tests and troubleshooting

The default test suite uses local/mock HTTP responses and does not require an
API key or public network access. It covers URL validation/canonicalization,
CRUD and archive behavior, API schemas, provider failure/quota paths, disabled
sources, and source-native response mapping.

- Provider `DISABLED`: enable `discovery.youtube.enabled` and the
  `YOUTUBE_WATCHLIST` source profile.
- Provider `UNCONFIGURED`: export the configured environment key and restart
  the Spring process so it inherits the variable.
- Refresh returns `FAILED` with `quotaExceeded`: check the YouTube API project
  quota and the local `discovery.youtube.daily-quota-unit-budget` setting.
- HTTP 403: inspect key restrictions, Data API enablement, and provider status;
  do not put the key in a URL that you share.
- Timeout: check connectivity and the existing `discovery.request-timeout`.
- Source URL rejected: use an HTTPS channel-ID/handle or video URL listed above.
- `CONFLICT` on duplicate: a source with the same canonical URL, country, and
  niche already exists; update that entry instead.

The current YouTube Developer Policies require API data other than certain
authorized statistics to be refreshed or deleted after 30 days. Review the
[official developer policies](https://developers.google.com/youtube/terms/developer-policies)
and [Data API reference](https://developers.google.com/youtube/v3/docs/) before
enabling API access; this application does not replace the developer's policy
or audit obligations.
