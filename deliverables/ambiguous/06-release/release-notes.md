## What changed
- Short URLs are built from the configured public origin rather than the incoming request `Host` header.
- Self-origin destinations are rejected using canonical host comparison, including case variants, trailing dots, and equivalent Unicode/IDN representations.
- Link creation is limited to 30 accepted requests per rolling minute per client connection address, per application instance. Requests over the limit return HTTP 429 using the standard error format.
- New links expire after 90 days by default. The creation request accepts optional integer `expiresInDays` values from 1 through 365.
- Expired links return HTTP 410 Gone with the standard error response and no `Location` header. Expiration is checked before click and redirect-event accounting.
- Rate-limit rejections and expired-link accesses are logged and recorded in the existing audit trail.
- Existing links with a null expiration remain non-expiring. Existing missing-link behavior, valid redirects, analytics behavior, and HTTP 503 handling for exhausted short-code generation are retained.

## API changes
- `POST /api/links` accepts optional `expiresInDays` and may return additive `expiresAt` data.
- Invalid, malformed, fractional, string, zero, negative, or greater-than-365 `expiresInDays` values return the existing HTTP 400 error format.
- `POST /api/links` returns HTTP 429 after the per-client rolling-window limit is reached.
- Expired `GET /{code}` requests return HTTP 410 with no redirect location.
- Link details responses may include `expiresAt`.

## Upgrade and compatibility notes
- Configure `app.public-origin` or the documented server-origin configuration before deployment. The approved behavior permits the configured localhost/server-port fallback; public deployments should provide their real public origin.
- Add the nullable `links.expires_at` column before relying on the new code in production. Existing rows with null expiration remain usable indefinitely.
- Rate limiting is in-memory and per application instance, not a distributed quota. It uses `request.getRemoteAddr()` and intentionally ignores forwarding headers.
- Review deployments behind NAT or proxies because multiple users may share one remote address.

## Known issues and follow-ups
- Inactive client entries in the in-memory rate-limiter map are not currently evicted and may grow with many distinct source addresses.
- Failure-audit persistence is logged but suppressed, so an audit record is not guaranteed if the audit repository is unavailable.
- The compatibility `ShortUrlBuilder` constructor still has a request-origin fallback for blank direct-construction input; the Spring-configured production path uses the validated configured origin. Removing that unreachable fallback is a follow-up.
- Redirect probing, request-body size limits, and malicious/private-network destination checks remain outside this change. External HTTP/HTTPS destinations continue to be allowed by design.