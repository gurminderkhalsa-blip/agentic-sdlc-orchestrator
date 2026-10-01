# URL Shortener

A Spring Boot URL shortener backed by Spring Data JPA and H2. The service creates short links, redirects requests to their stored destinations, reports link details, and provides database-backed redirect analytics.

## Requirements

- Java 21
- The Gradle wrapper included in the repository

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew bootRun
```

The default server port is `8080`.

## Test

```bash
./gradlew test
```

Gradle writes test reports under `build/reports/tests/`.

## Configuration

Runtime configuration is in `src/main/resources/application.yml` and can be overridden with standard Spring configuration mechanisms or environment variables.

| Property | Default | Purpose |
|---|---|---|
| `app.public-origin` | `http://localhost:${server.port}` | Public origin used in generated `shortUrl` values and self-link detection. It is also available through `APP_PUBLIC_ORIGIN`. |
| `app.time-zone` | `UTC` | Server timezone used for redirect-event calendar days and the 30-day analytics window. It is also available through `APP_TIME_ZONE`. |
| `server.port` | `8080` | HTTP listening port. |

The public origin is configuration-driven. The service does not derive it from an incoming request `Host` header. Configure a usable public origin for deployments behind a proxy, for example:

```yaml
app:
  public-origin: https://short.example.com
  time-zone: Europe/London
server:
  port: 8080
```

Destinations must be absolute HTTP or HTTPS URLs, must be no longer than 2048 characters, and must not target the configured shortener origin and redirect URL space. These validation failures use the existing invalid-URL response.

The service uses the configured H2/JPA settings for persistence. Do not put credentials or other secrets in source control.

## API examples

Generated codes contain seven URL-safe characters. The examples use `Ab3xYz9` as a placeholder; replace it with a code returned by the create operation.

### Create a link — `POST /api/links`

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/path?q=1"}'
```

Successful response: `201 Created`.

```json
{
  "code": "Ab3xYz9",
  "shortUrl": "http://localhost:8080/Ab3xYz9",
  "originalUrl": "https://example.com/path?q=1",
  "clickCount": 0
}
```

Invalid, blank, malformed, unsupported, self-referential, or over-2048-character destinations return `400 Bad Request` with detail `url must be an absolute HTTP or HTTPS URL`. Exhausting the short-code collision retry limit returns `503 Service Unavailable` with detail `Unable to create a short link`.

### Redirect — `GET /{code}`

Use `-i` without `-L` to inspect the redirect. The optional `Referer` header is recorded with the redirect event.

```bash
curl -i http://localhost:8080/Ab3xYz9
```

A successful redirect returns `302 Found`, a `Location` header containing the stored destination, and no JSON response body. It atomically increments the legacy click count and persists one redirect event.

```bash
curl -i -H 'Referer: https://news.example/' http://localhost:8080/Ab3xYz9
```

Unknown or malformed codes return `404 Not Found` with detail `Short link not found`.

### Get link details — `GET /api/links/{code}`

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9
```

Successful response: `200 OK`.

```json
{
  "code": "Ab3xYz9",
  "shortUrl": "http://localhost:8080/Ab3xYz9",
  "originalUrl": "https://example.com/path?q=1",
  "clickCount": 1
}
```

Unknown or malformed codes return `404 Not Found` with detail `Short link not found`.

### Get analytics — `GET /api/links/{code}/analytics`

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9/analytics
```

Successful response: `200 OK`. `clicksPerDay` always contains 30 calendar-day buckets, oldest first, using `app.time-zone`. `topReferrers` excludes missing or blank referrers and contains at most five entries.

```json
{
  "totalClicks": 12,
  "clicksPerDay": [
    {"date": "2026-09-02", "count": 0},
    {"date": "2026-09-03", "count": 2}
  ],
  "topReferrers": [
    {"referrer": "https://example.org", "count": 5}
  ]
}
```

The example is abbreviated; the actual `clicksPerDay` array contains exactly 30 entries. Unknown or malformed codes return the existing `404 Not Found` response. Database or unexpected analytics failures return `500 Internal Server Error` with detail `An unexpected server error occurred`.

## Analytics and persistence

Each successful redirect stores a `redirect_events` row containing the link relationship, an `event_time`, the configured-timezone `event_day`, and the optional `Referer` value. The legacy `clickCount` remains available and unchanged for existing responses.

Analytics uses database queries for the total count, date-bounded daily grouping, and grouped referrer ranking with a database limit of five. Redirect events are not loaded into application memory as a collection.

## Logging

SLF4J/Spring Boot logs go to the application process's standard console output by default. No application-specific file appender is configured; deployments can collect or redirect standard output through their process manager or container platform.

The service logs:

- `INFO` for successful link creation, redirects, and analytics requests;
- `WARN` for self-link and over-length creation rejection, invalid or missing codes, and code-collision retries;
- `ERROR` for redirect-event or audit persistence failures, exhausted code generation, analytics failures, and unexpected failures.

Logs include bounded operation context such as an operation, short code, outcome, and exception type where applicable. Full destination URLs, request bodies, credentials, authorization headers, secrets, and full referrers are not logged.

## Audit trail

Audit records are stored in the `audit_events` table. Existing actions remain supported, and the new behavior adds:

| Action | Recorded when | Outcome examples |
|---|---|---|
| `LINK_CREATED` | A link is created | `SUCCESS` |
| `CREATION_REJECTED` | Link creation is rejected | `SELF_LINK`, `URL_TOO_LONG`, or an existing invalid-request outcome |
| `REDIRECTED` | A redirect succeeds | `REDIRECTED` |
| `UNKNOWN_CODE` | A valid-format code is not found | `NOT_FOUND` |
| `ANALYTICS_REQUEST` | Analytics is requested | `SUCCESS`, `NOT_FOUND`, or `ERROR` |

Audit records include the service timestamp, safe bounded code context where applicable, client address according to the existing audit model, and outcome. Rejected creation records do not persist a generated code. Raw destinations and referrers are not written to the audit trail.

Successful redirect auditing and redirect-event persistence participate in the redirect transaction, so a successful redirect is not returned if required persistence fails. Analytics not-found and error outcomes are audited, and rejected self-links and overlong destinations are audited before returning their `400` response.
