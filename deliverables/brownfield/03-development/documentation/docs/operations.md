# Operations

## Configuration

The public origin is configured through `app.public-origin` or the `APP_PUBLIC_ORIGIN` environment variable. The default is `http://localhost:${server.port}`. It is used both to construct `shortUrl` response values and to reject destinations that point back into the shortener's redirect URL space. The incoming request `Host` header is never used to determine the public origin.

Set the analytics calendar timezone with `app.time-zone` or `APP_TIME_ZONE`; the default is `UTC`. Redirect events retain an `Instant` timestamp and a persisted calendar day calculated in this timezone.

Example deployment configuration:

```yaml
app:
  public-origin: https://short.example.com
  time-zone: Europe/London
server:
  port: 8080
```

## Logging

SLF4J/Spring Boot writes logs to the application process's standard console output by default. No application-specific file appender is defined; a process manager, container runtime, or platform can collect or redirect standard output.

Typical operations and levels are:

- `INFO`: successful `LINK_CREATED`, `REDIRECTED`, and `ANALYTICS_REQUEST` operations;
- `WARN`: `CREATION_REJECTED` outcomes including `SELF_LINK` and `URL_TOO_LONG`, missing or invalid codes, and short-code collision retries;
- `ERROR`: redirect-event or audit persistence failures, analytics processing failures, exhausted code-generation retries, and unexpected exceptions.

Log entries contain enough bounded context to identify the operation, code, and outcome. They do not contain credentials, authorization headers, secrets, request bodies, full destination URLs, or full referrer values.

## Audit trail

Audit records are persisted in the `audit_events` table through Spring Data JPA. The existing fields include service timestamp, action, code, client address, and outcome.

| Action | Recorded when | Outcome examples |
|---|---|---|
| `LINK_CREATED` | A link insert succeeds | `SUCCESS` |
| `CREATION_REJECTED` | Link creation is rejected before persistence | `SELF_LINK`, `URL_TOO_LONG`, `INVALID_URL` |
| `REDIRECTED` | A known short link redirects | `REDIRECTED` |
| `UNKNOWN_CODE` | A valid-format code is absent for an existing endpoint | `NOT_FOUND` |
| `ANALYTICS_REQUEST` | Analytics is requested | `SUCCESS`, `NOT_FOUND`, `ERROR` |

A rejected self-link or overlong destination creates a `CREATION_REJECTED` audit row without creating a link. Every analytics request produces an `ANALYTICS_REQUEST` audit outcome, including not-found and error outcomes. Raw destination URLs and referrers are not recorded in the audit trail.

Successful redirect-event persistence and redirect auditing participate in the redirect transaction. If either required write fails, the redirect does not return a successful `302`. Analytics failure outcomes and rejected creation outcomes are retained according to the existing audit service transaction behavior.

## Redirect events and analytics

Each successful redirect creates exactly one row in `redirect_events` containing:

- the associated link;
- `event_time`, persisted as a timestamp;
- `event_day`, calculated in `app.time-zone`;
- the `Referer` header, or null when absent or blank.

The analytics service executes bounded database queries for total clicks, daily grouping over the current day and preceding 29 days, and top-referrer grouping with a database limit of five. It fills missing daily dates with zeroes in the response and does not fetch all redirect events into application memory.

The legacy link `clickCount` remains separate and continues to be updated atomically for compatibility with existing responses.

## Storage and monitoring

The service stores links in `links`, redirect events in `redirect_events`, and audit records in `audit_events`. The redirect-event table includes indexes for link/day and link/referrer access. Monitor console logs and database persistence errors, particularly `REDIRECTED`, `ANALYTICS_REQUEST`, and `CREATION_REJECTED` outcomes.
