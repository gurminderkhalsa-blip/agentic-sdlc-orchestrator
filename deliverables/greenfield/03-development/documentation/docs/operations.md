# Operations

## Logging

The service uses SLF4J through Spring Boot. With the repository defaults, logs are written to the application process's standard console output. No application-specific file appender is defined; a process manager, container runtime, or platform may collect or redirect standard output.

Log levels and actions are:

- `INFO`: successful `LINK_CREATED` and `REDIRECTED` operations, including the short code and outcome.
- `WARN`: `CREATION_REJECTED`, `UNKNOWN_CODE`, and short-code collision retries.
- `ERROR`: audit persistence failures, retry exhaustion, unexpected request-processing failures, and infrastructure failures.

The service does not log the full original URL, request body, credentials, secrets, or configuration-sensitive values. Client IP addresses are stored in audit rows but are not included in normal success logs. Invalid code values are validated before they are logged. HTTP error bodies do not contain stack traces or database details.

## Audit trail

Audit events are persisted through Spring Data JPA in the H2 table `audit_events`:

| Column | Meaning |
|---|---|
| `event_time` | Service-generated event timestamp |
| `action` | `LINK_CREATED`, `CREATION_REJECTED`, `REDIRECTED`, or `UNKNOWN_CODE` |
| `code` | Generated/requested code when available; null for rejected creation |
| `client_ip` | Servlet request remote address, or `unknown` when unavailable |
| `outcome` | `SUCCESS`, `INVALID_URL:<reason>`, `INVALID_REQUEST:<reason>`, `REDIRECTED`, or `NOT_FOUND` |

The implemented audit behavior is:

- `LINK_CREATED` is written in the same transaction as the link insert. A failure rolls back the link insert, and a successful link cannot commit without its creation audit row.
- `CREATION_REJECTED` is written by the exception handler in a new transaction for invalid, missing, blank, malformed, unsupported-scheme, and overlong creation requests.
- `REDIRECTED` is written after a successful atomic click-count update and joins that transaction. If the audit write fails, the redirect operation fails and the state change is rolled back.
- `UNKNOWN_CODE` is written in a new transaction for valid-format codes that are not found by either the redirect or details endpoint. Invalid-format codes are rejected before lookup, logging, or auditing.

Audit writes are not silently swallowed. Persistence failures are logged and propagated, producing an appropriate server error rather than an apparently successful operation without its required audit record.

Links are stored in the H2 `links` table. Redirect click counts are incremented with an atomic update, preventing lost increments when redirects run concurrently. H2 data is not expected to survive application restarts.

## Runtime configuration

The optional `shortener.public-base-url` setting controls the public base used in response `shortUrl` values. If it is absent or blank, the service derives the base from the request scheme, host, port, and context path. Forwarded headers are not trusted.
