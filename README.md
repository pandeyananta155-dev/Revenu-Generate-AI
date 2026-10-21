# Trend-to-Revenue AI Factory

Phases 1–4 provide a local Spring Boot REST foundation, validated YAML
catalogs, persisted source profiles and offers, source-attributed trend
discovery, YouTube watchlists, and personal research notes. Scoring, content
generation, rendering, and publishing are intentionally not implemented.

## Technology choices

- **Java 21** is the project language baseline requested by the playbook.
- **Spring Boot 4.1.1** is pinned as the stable version named in the project
  playbook.
- **springdoc-openapi 3.0.1** supplies Swagger UI and the OpenAPI document and
  is in the 3.x line specified for Spring Boot 4.
- **Maven Wrapper 3.9.11** makes builds repeatable without requiring a
  separately installed Maven.
- **JUnit 5** is used through Spring Boot's test starter.
- **SQLite JDBC / Spring JdbcClient** provides local persistence without an
  external database server or ORM.
- **Apache Commons CSV 1.14.1** parses bounded user-supplied Google Trends
  exports without adding a spreadsheet runtime or evaluating formulas.

## macOS setup

Install a Java 21 JDK. With Homebrew:

```bash
brew install openjdk@21
```

Select the Homebrew JDK in the current terminal and verify it:

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

The output must report Java 21. If you already have a registered Java 21 JDK,
you can instead set `JAVA_HOME` with
`export JAVA_HOME="$(/usr/libexec/java_home -v 21)"`. From the repository root,
run tests and start the app:

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

The server binds to `127.0.0.1:8080` by default. Verify the API from another
terminal:

```bash
curl -i http://127.0.0.1:8080/api/v1/health
```

Expected response:

```json
{
  "status": "UP",
  "application": "Trend-to-Revenue AI Factory",
  "version": "0.1.0"
}
```

## API documentation

- Swagger UI: <http://127.0.0.1:8080/swagger-ui.html>
- OpenAPI JSON: <http://127.0.0.1:8080/v3/api-docs>

The Swagger UI route is explicitly configured in `src/main/resources/application.yml`.

## Configuration and Phase 2 APIs

Configured markets and niches can be extended in `src/main/resources/application.yml`;
sample catalog files are in `examples/config/`. Provider and offer changes made
through the API are stored in SQLite.

- `GET /api/v1/markets`
- `GET /api/v1/niches`
- `GET /api/v1/providers`, `POST /api/v1/providers`,
  `PATCH /api/v1/providers/{code}`
- `GET /api/v1/offers`, `POST /api/v1/offers`,
  `PATCH /api/v1/offers/{code}`

Additional details about permission guards, validation, database migrations,
and backups are in [docs/configuration-and-storage.md](docs/configuration-and-storage.md).

## Phase 3: trend discovery

Discovery is documented in [docs/PHASE_03_TREND_DISCOVERY.md](docs/PHASE_03_TREND_DISCOVERY.md).
The API reports provider availability and policy blocks; default providers are
disabled until explicitly configured. Reddit is always blocked and is never
queried. Start with:

```bash
curl -s http://127.0.0.1:8080/api/v1/discovery/providers
curl -s -X POST http://127.0.0.1:8080/api/v1/discovery/scans \
  -H 'Content-Type: application/json' \
  -d '{"marketCode":"US","nicheCode":"AI_DEV_TOOLS","windowDays":7}'
```

The second command runs only enabled providers. A Google Trends CSV can be
imported with explicit market and niche context:

```bash
curl -i -X POST http://127.0.0.1:8080/api/v1/discovery/google-trends/import \
  -F 'file=@trends.csv;type=text/csv' \
  -F 'countryCode=US' \
  -F 'nicheCode=AI_DEV_TOOLS'
```

The response includes a `scanId`; fetch its evidence with
`GET /api/v1/discovery/scans/{scanId}/results`. Swagger UI and OpenAPI JSON
remain available at the URLs above.

## Phase 4: watchlists and research notes

You can manage normalized YouTube channel/video sources and linked personal
notes through the API. Optional initial sources can be seeded under
`watchlists.sources` in [application.yml](src/main/resources/application.yml);
after startup, the REST API manages their SQLite records. Refreshes are manual
and use the official YouTube Data API only. See
[docs/PHASE_04_CHANNEL_WATCHLISTS.md](docs/PHASE_04_CHANNEL_WATCHLISTS.md) for
configuration, routes, refresh behavior, and retention details.

## Configuration and errors

Application settings and catalogs, loopback bind address, and port are
configured in `src/main/resources/application.yml`. The local database folder
is configurable with `APP_DATA_DIRECTORY`. Spring Boot's standard environment
overrides are available, for example:

```bash
SERVER_PORT=8090 ./mvnw spring-boot:run
```

API errors use RFC 9457 `ProblemDetail` responses. Validation failures return
HTTP 400 with field errors; unexpected errors are logged server-side and
return a generic message without exception details.
