## Summary
Greenfield Spring Boot URL shortener service backed by Spring Data JPA and the configured H2 database.

## User-visible changes
- `POST /api/links` accepts `{ "url": "https://example.com/path" }` and returns HTTP 201 with `originalUrl`, a unique seven-character URL-safe `code`, `shortUrl`, and `clickCount` initialized to 0.
- Duplicate URL submissions create separate links and may receive different codes.
- `GET /{code}` returns HTTP 302 with a `Location` header for known codes and atomically increments the persisted click count.
- Unknown or structurally invalid codes return HTTP 404 without a redirect location.
- `GET /api/links/{code}` returns HTTP 200 with link details and the current click count, or HTTP 404 when the link is unknown.
- Invalid, missing, relative, unsupported-scheme, malformed, or overlong URLs return HTTP 400 with a machine-readable Problem Detail and a clear validation message.
- Operational logs cover creation, rejected creation, redirects, unknown codes, and unexpected failures without exposing full destination URLs or sensitive exception details.
- Audit events are persisted in H2 for successful creation, rejected creation, successful redirects, and unknown-code requests, including service timestamp, action, code where applicable, client IP, and outcome.

## API and compatibility notes
- This is a greenfield API under the `com.example.shortener` package; no prior public API compatibility is required.
- Codes are generated with `SecureRandom`, contain seven alphanumeric URL-safe characters, and are protected by a database uniqueness constraint with bounded collision retries.
- The service preserves accepted destination URL text after JSON parsing and performs syntax validation only; it does not verify reachability or safety of destinations.
- Short URLs use the configured public base URL when provided. Otherwise, they are derived from the direct servlet request origin; forwarded headers are not trusted.
- H2 data is runtime-only and is not expected to survive application restarts.

## Known issues and accepted risks
- Request bodies do not have a configured maximum size before JSON deserialization.
- There is no rate limiting, quota, or abuse detection for creation, lookup, redirect, or unknown-code requests.
- Without a configured public base URL, an untrusted Host header can influence the returned `shortUrl`.
- The service intentionally supports redirects to arbitrary absolute HTTP(S) destinations, so it can be used as an open redirect; malware scanning and destination policy are out of scope.
- These medium security risks were reviewed and explicitly accepted for this release and deferred to the safety scenario.