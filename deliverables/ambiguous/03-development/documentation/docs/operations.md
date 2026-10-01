# Operations

## Startup and configuration

Configure a valid public origin before starting the service:

```yaml
app:
  public-origin: https://short.example.com
  time-zone: UTC
server:
  port: 8080
```

The same values can be supplied with `APP_PUBLIC_ORIGIN`, `APP_TIME_ZONE`, and `SERVER_PORT`. `app.public-origin` is required and must be an absolute HTTP or HTTPS origin with a host. It may include a path prefix, but not user information, a query, or a fragment. The application fails configuration/startup for a missing or invalid origin rather than using the request `Host` header.

The configured origin is used for generated `shortUrl` values in creation and detail responses. Redirect responses use the stored validated destination in `Location`; they do not construct a destination from request metadata or the configured public origin.

The service clock defaults to UTC system time. Expiration timestamps and audit timestamps use that clock. The analytics calendar uses `app.time-zone`, which defaults to UTC.

## Expiration operations

New links default to a 90-day expiration. Callers can select an integer duration from 1 through 365 days with `expiresInDays`. The resulting `expiresAt` is persisted in `links.expires_at`.

The request value must be a JSON integer. Fractional numbers and quoted numeric strings are rejected with `400 Bad Request`. Expiration is exact: a link is valid while the current service instant is before `expiresAt` and expired when the current instant is equal to or later than it.

Expired redirects return `410 Gone`, do not emit `Location`, do not increment clicks, and do not create redirect-event analytics rows. The expiration column is nullable for compatibility. Existing links with null `expiresAt` are treated as non-expiring.

## Rate limiting

Creation is limited to 30 admitted requests per direct remote address in a rolling one-minute window. The limiter is atomic per client within one application instance and is held in memory. It is not a distributed quota and does not use the database.

The client identifier is `HttpServletRequest.getRemoteAddr()`. `X-Forwarded-For` and other forwarding headers are ignored. A deployment requiring a different client identity must provide the desired remote address at the connection boundary. This per-instance limitation is an accepted operational characteristic of the service.

A rejected request returns `429 Too Many Requests` before URL validation, short-code generation, or persistence. It therefore creates no link and consumes no generator attempt.

## Logging

SLF4J/Spring Boot logs are written to standard console output by default. There is no application-specific file appender. Collect or route console output through the service manager, container runtime, or platform logging system.

Expected levels and events:

- `INFO`: successful link creation and successful redirects;
- `WARN`: rate-limit rejection, expired-link access, invalid or missing codes, invalid creation requests, and code-collision retries;
- `ERROR`: exhausted code generation, unexpected failures, and audit persistence failures.

Relevant structured-style fields include action, short code where safe, client identifier where required, and outcome. Logs exclude full original URLs, request bodies, authorization headers, credentials, passwords, API keys, and unnecessary referrer data. Client identifiers are bounded where they are logged.

Search for `RATE_LIMIT_REJECTED` and `EXPIRED_LINK_ACCESS` to investigate abuse or visitors reaching expired links. Search for `GENERATION_EXHAUSTED` and audit persistence failures to identify service or storage problems.

## Audit trail

Operational audit records use the existing `audit_events` table and repository. The table records the service event timestamp, action, optional short code, client address/identifier, and outcome.

| Action | Trigger | Recorded information |
|---|---|---|
| `RATE_LIMIT_REJECTED` | Each HTTP 429 creation rejection | Action, direct client identifier, service timestamp, `LIMIT_EXCEEDED` outcome |
| `EXPIRED_LINK_ACCESS` | Each HTTP 410 expired-link access | Action, short code, service timestamp, `GONE` outcome |
| `LINK_CREATED` | Successful link persistence | Short code, timestamp, success outcome |
| `CREATION_REJECTED` | URL or request validation failure | Timestamp, client identifier, safe validation outcome |
| `REDIRECTED` | Successful non-expired redirect | Short code, timestamp, redirect outcome |
| `UNKNOWN_CODE` | Missing or malformed link resolution | Safe code context, timestamp, not-found outcome |
| `ANALYTICS_REQUEST` | Existing analytics audit behavior | Existing operation outcome |

Rate-limit and expired-access audit events are written through an independent failure path. If persistence of one of these events fails, the failure is logged and the HTTP `429` or `410` result is retained rather than replaced by an audit database error.

Audit records do not contain full destination URLs, credentials, API keys, passwords, request bodies, or unnecessary referrer data. Existing audit retention and operational policies continue to apply; this change introduces no separate retention policy.

## Redirect events and analytics

Each successful redirect retains the existing `redirect_events` behavior: it stores the link, event timestamp, configured-timezone calendar day, and optional bounded `Referer` value. Expired accesses occur before click-count and redirect-event persistence, so they are excluded from redirect analytics.

The legacy `clickCount` remains separate from redirect-event analytics and continues to be updated for valid redirects. Analytics and missing-link behavior remain unchanged except for the addition of expiration information to link responses.
