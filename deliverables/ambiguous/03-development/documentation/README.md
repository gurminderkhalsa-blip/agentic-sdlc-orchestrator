# URL Shortener

A Spring Boot URL-shortening service backed by Spring Data JPA and H2. It creates short links, redirects visitors to validated destinations, exposes link details, and provides click analytics.

New links use the configured public origin, are limited to 30 admitted creations per client address per rolling minute, and expire by default after 90 days. Legacy links with a null expiration remain non-expiring.

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

The default HTTP port is `8080`. Set `app.public-origin` before starting the service; startup fails when the effective origin is missing or invalid.

## Test

```bash
./gradlew test
```

Test reports are written under `build/reports/tests/`.

## Configuration

Deployment-specific values should be supplied through Spring configuration or environment variables.

| Property | Environment variable | Default | Purpose |
|---|---|---:|---|
| `app.public-origin` | `APP_PUBLIC_ORIGIN` | None; required | Absolute HTTP/HTTPS origin used for generated `shortUrl` values and self-origin validation |
| `app.time-zone` | `APP_TIME_ZONE` | `UTC` | Calendar time zone used by redirect analytics |
| `server.port` | `SERVER_PORT` | `8080` | HTTP listening port |

Example:

```yaml
app:
  public-origin: https://short.example.com
  time-zone: UTC
server:
  port: 8080
```

`app.public-origin` may include a path prefix, such as `https://short.example.com/links`. It must be an absolute HTTP or HTTPS origin with a host and no user information, query, or fragment. It is validated while the application context starts. The service never uses the incoming request `Host` header and does not provide a request-host fallback.

Creation is limited to 30 admitted requests from one direct remote address in a rolling one-minute window per application instance. `X-Forwarded-For` and other forwarding headers are ignored. The limiter is in-memory and is not a distributed quota.

`expiresInDays` is optional. Omitted values select 90 days; supplied JSON values must be integers from 1 through 365. Expiration is evaluated at an exact service-clock instant: a link expires when the current instant is equal to or later than `expiresAt`.

## API examples

Generated codes contain seven URL-safe characters. The examples use `Ab3xYz9` as a placeholder; replace it with a code returned by the create operation.

### Create a link — `POST /api/links`

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/path?q=1","expiresInDays":30}'
```

Successful creation returns `201 Created`:

```json
{
  "code": "Ab3xYz9",
  "shortUrl": "https://short.example.com/Ab3xYz9",
  "originalUrl": "https://example.com/path?q=1",
  "clickCount": 0,
  "expiresAt": "2026-10-31T12:00:00Z"
}
```

The response `shortUrl` uses only the configured public origin, regardless of the request `Host` header. Invalid input returns `400`; an over-limit request returns `429`; exhaustion of the existing bounded code-generation retries returns `503`.

### Redirect — `GET /{code}`

Use `-i` without `-L` to inspect the redirect:

```bash
curl -i http://localhost:8080/Ab3xYz9
```

A valid, non-expired link returns `302 Found`:

```http
Location: https://example.com/path?q=1
```

The `Location` value is the stored validated destination. An expired link returns `410 Gone` with no `Location` header. A missing or malformed code returns `404 Not Found`.

### Get link details — `GET /api/links/{code}`

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9
```

The `200 OK` response includes the configured-origin short URL, stored destination, click count, and `expiresAt`. Legacy records may return `"expiresAt": null`.

### Get analytics — `GET /api/links/{code}/analytics`

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9/analytics
```

The `200 OK` response includes total clicks, daily click objects, and top referrer objects. Missing or malformed codes return `404 Not Found`.

## Logging

Spring Boot and SLF4J logs go to the application process's standard console output. No application-specific file appender is configured; a process manager or container platform can collect the output.

The service logs successful creations and redirects at `INFO`. Rate-limit rejections, expired-link accesses, invalid or missing codes, validation failures, and code-collision retries are logged at `WARN`. Code-generation exhaustion, unexpected failures, and audit persistence failures are logged at `ERROR`.

Operational entries contain bounded action, short-code, client-identifier, and outcome fields where applicable. Full destinations, request bodies, credentials, authorization data, API keys, passwords, and unnecessary URL data are not logged.

## Audit trail

Audit records are stored in the existing `audit_events` table. Records contain an event timestamp, action, optional short code, client address/identifier, and outcome.

| Action | Recorded when | Typical outcome |
|---|---|---|
| `LINK_CREATED` | A link is persisted successfully | `SUCCESS` |
| `CREATION_REJECTED` | Creation validation fails | Validation reason |
| `RATE_LIMIT_REJECTED` | A creation request exceeds 30 requests in the rolling minute | `LIMIT_EXCEEDED` |
| `REDIRECTED` | A known non-expired link redirects | `REDIRECTED` |
| `EXPIRED_LINK_ACCESS` | A visitor requests an expired link | `GONE` |
| `UNKNOWN_CODE` | A code is missing or does not identify a link | `NOT_FOUND` |
| `ANALYTICS_REQUEST` | Existing analytics processing records this action | Existing analytics outcome |

Every `429` creation rejection and every `410` expired-link access produces an audit event and an application log entry. These failure audits use an independent transaction path; if audit persistence fails, the intended HTTP response is retained and the persistence failure is logged. Audit records do not contain full destination URLs or credentials.

## Persistence compatibility

`links.expires_at` is nullable. A null value represents a legacy link created before expiration support and is treated as non-expiring. Redirect events remain stored in `redirect_events`, and click-count and analytics behavior is unchanged for valid redirects.
