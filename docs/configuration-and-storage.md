# Configuration and local storage

## Runtime configuration

The application binds and validates `app.*` settings from
`src/main/resources/application.yml`. The sample files in `examples/config/`
show how to define market, niche, provider, offer, and YouTube watchlist
catalog entries. Copy the desired entries under the matching
`app.catalogs.*` key in `application.yml`; keep each code unique within its
catalog. Add a new market or niche by adding a YAML entry, not by changing Java
code.

Configuration failures stop application startup. Validation messages identify
the `app.catalogs` field and the expected value (for example, an ISO country
code, IANA timezone, absolute HTTP(S) URL, non-negative retention period, or
unique code). Correct the indicated YAML key and restart.

Provider restrictions are checked both at startup and at the write API:

- Reddit remains disabled for commercial use unless a written permission
  reference is present.
- YouTube scoring/content-grounding requires an accepted derived-metrics
  approval reference.
- `media-download` cannot be enabled for public URL providers.
- Secret values are never configuration fields or API output. Profiles contain
  only environment-variable names.

The API can edit provider endpoints, enablement, permissions within those
policy gates, terms review metadata, retention, approval references, and
environment-variable names. Offer API writes can edit offer claims, destination,
supported markets, disclosure, and status. Updates are persisted in SQLite and
are not overwritten by YAML catalog seeding at next startup; the configured
catalog only seeds a code that is not already present.

## SQLite and backup

The default local database is `data/trend-to-revenue.db`. Change the folder
with `APP_DATA_DIRECTORY`:

```bash
APP_DATA_DIRECTORY="$HOME/Library/Application Support/trend-to-revenue" ./mvnw spring-boot:run
```

Schema versions 1, 2, and 3 are recorded in `schema_migrations`. Startup applies each
missing version and uses `CREATE TABLE IF NOT EXISTS`; it does not drop tables
or remove stored records. The database includes tables for scan runs, evidence,
opportunities, drafts, offers, review decisions, publications, and source
profiles. Version 2 adds discovery scans and results, Google Trends CSV import
provenance, and daily YouTube quota-unit accounting. Version 3 adds
`watchlist_sources`, `watchlist_refresh_history`, and `research_notes`; archive
operations preserve source history and linked notes. YouTube refresh metadata
is pruned after its configured retention period (at most 30 days), on startup,
on history access, and by a daily local cleanup task. Discovery evidence is
removed after its configured `delete_after` deadline when a discovery scan or
result request next runs; no background cleanup service is started.

To back up the SQLite file safely, stop the application first, then copy the
database file to a protected local destination:

```bash
mkdir -p "$HOME/Backups/trend-to-revenue"
cp -p data/trend-to-revenue.db \
  "$HOME/Backups/trend-to-revenue/trend-to-revenue-$(date +%Y%m%d-%H%M%S).db"
```

To export a portable SQL text dump (requires the macOS `sqlite3` command),
stop the application and run:

```bash
sqlite3 data/trend-to-revenue.db .dump \
  > "$HOME/Backups/trend-to-revenue/trend-to-revenue-$(date +%Y%m%d-%H%M%S).sql"
```

Keep backups private because future phases may store user-created research and
workflow state. To restore, stop the application, preserve the current
database as a separate backup, then copy the chosen backup to the configured
database path. To restore a SQL dump into a new database file, use
`sqlite3 restored.db < backup.sql`.

## Phase 2 endpoints

- `GET /api/v1/markets`
- `GET /api/v1/niches`
- `GET /api/v1/providers` and `/api/v1/providers/{code}`
- `POST /api/v1/providers` and `PATCH /api/v1/providers/{code}`
- `GET /api/v1/offers` and `/api/v1/offers/{code}`
- `POST /api/v1/offers` and `PATCH /api/v1/offers/{code}`

OpenAPI/Swagger documents the request and response schemas at the URLs listed
in the main README.

Phase 3 provider controls, endpoint semantics, retention, and troubleshooting
are documented in [PHASE_03_TREND_DISCOVERY.md](./PHASE_03_TREND_DISCOVERY.md).
Phase 4 watchlists and research notes are documented in
[PHASE_04_CHANNEL_WATCHLISTS.md](./PHASE_04_CHANNEL_WATCHLISTS.md).
