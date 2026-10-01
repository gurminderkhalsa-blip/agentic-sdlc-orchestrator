## Plan and rationale
Implemented the approved brownfield change across configuration, URL validation and construction, creation admission control, expiration persistence and enforcement, audit/logging, API models, and regression tests. The design preserves existing redirect, analytics, missing-link, and code-generation-exhaustion behavior while adding configured-origin safety, canonical self-origin checks, per-instance rate limiting, and exact-instant expiration.

## Artifacts produced
- Validated `PublicOriginProperties` and injectable UTC service clock.
- Nullable `Link.expiresAt`, request-side `expiresInDays` parsing/validation, and additive response `expiresAt`.
- Configured-origin `ShortUrlBuilder` and canonical host validation using case folding, trailing-dot removal, and IDN-to-ASCII conversion.
- Atomic in-memory rolling-window `CreationRateLimiter` with a 30-request limit per remote address per minute and per application instance.
- HTTP 429 and HTTP 410 exception mappings using the existing ProblemDetail format.
- Expiration-aware creation and redirect handling, including legacy null-expiration compatibility.
- Rate-limit and expired-access audit events and structured application log entries.
- API and operations documentation updates.
- Regression, integration, unit, and concurrency test coverage across the changed behavior.

## Validation performed (tests, coverage, security review, gates)
- 54 tests passed; 0 failures, 0 errors, and 0 skipped.
- Reported line coverage was 93.7%, above the stated 90% minimum.
- All 15 acceptance criteria were reported as covered, with no uncovered criteria or phantom test cases.
- Tests cover configured-origin independence from request `Host`, canonical host variants, strict integer expiration parsing, default and custom expiration, exact expiration behavior, legacy links, 429 admission boundaries, concurrent limiter atomicity, independent client addresses, standard error responses, audit records, and logging behavior.
- Security review completed. It identified the known limiter-state retention issue, redirect probing exposure, request-size-limit gap, audit persistence observability gap, and compatibility-constructor fallback. Open malicious/private-network destination scanning remains explicitly out of scope.
- Human review clarified that AC3 is satisfied by using `app.public-origin` or the approved server configuration fallback; startup failure for a missing origin is not required. Human-approved follow-ups remain documented below.

## Risks and trade-offs
- Rate limiting is atomic within one JVM but does not enforce an aggregate limit across horizontally scaled instances; this is an explicitly approved binding decision.
- Direct remote-address limiting avoids trusting spoofable forwarding headers but can combine users behind NAT or a proxy.
- Nullable expiration preserves pre-change links as non-expiring, but production schema rollout must add `links.expires_at` before deployment.
- Audit failure handling preserves the intended 429/410 user response but can reduce audit completeness when persistence is unavailable.
- External redirects remain possible because malicious-URL scanning, reputation checks, and private-network blocking are out of scope.
- The current implementation does not evict inactive limiter keys, creating a potential memory-growth risk under many distinct source addresses.

## Assumptions
- `app.public-origin` is preferred, with the approved existing server configuration/localhost server-port fallback permitted.
- The client identifier is `HttpServletRequest.getRemoteAddr()`; `X-Forwarded-For` and similar headers are not trusted.
- The rolling window is exactly one minute, with the first 30 requests admitted and subsequent requests rejected while those admissions remain in the window.
- Expiration is evaluated at an exact instant: a link is expired when the service clock is at or after `expiresAt`.
- Null `expiresAt` means never expiring for links created before this change.
- Existing audit retention and database policies apply.

## Limitations and follow-ups
- Add bounded eviction or inactivity cleanup for limiter entries and a regression test for cleanup.
- Remove the now-unreachable request-host fallback from the blank-origin compatibility constructor and update direct unit-test callers as needed.
- Add an alertable operational mechanism or durable retry path for failed failure-audit writes.
- Consider server request-size limits and read-side abuse controls in a future security hardening change.
- Confirm production schema migration of the nullable expiration column before rollout.

## Rollback plan
The release is represented by the git commits on the run branch. To roll back, revert the release change commits in reverse dependency order, or revert the complete release commit range as a coordinated change. If the schema has already been migrated, retain the nullable `links.expires_at` column during rollback because removing it is not required for the prior application behavior and could complicate recovery. Validate startup, link creation, redirects, and analytics after rollback.