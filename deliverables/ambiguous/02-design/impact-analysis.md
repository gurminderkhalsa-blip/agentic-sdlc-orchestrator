# Impact analysis (brownfield)

Produced by the Impact Analyst agent from the real repository; the impactFilesExist gate verified every file it names.

## Impacted files

| Change | File | Reason |
|---|---|---|
| modify | src/main/java/com/example/shortener/api/CreateLinkRequest.java | Add optional expiresInDays input with integer/range validation for values 1 through 365. |
| modify | src/main/java/com/example/shortener/api/LinkResponse.java | Expose the persisted effective expiration timestamp while retaining all existing response fields. |
| modify | src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java | Map rate-limit rejections to HTTP 429 and expired-link access to HTTP 410 using the existing ProblemDetail error format. |
| modify | src/main/java/com/example/shortener/controller/LinkController.java | Pass expiresInDays to link creation and preserve request-aware creation handling. |
| modify | src/main/java/com/example/shortener/domain/Link.java | Persist expiresAt for newly created links, allowing null for pre-change links that must never expire. |
| create | src/main/java/com/example/shortener/exception/ExpiredLinkException.java | Represent an expired short link so redirect handling can return HTTP 410 without issuing a Location header. |
| create | src/main/java/com/example/shortener/exception/RateLimitExceededException.java | Represent creation requests rejected after the per-IP rolling-window limit. |
| modify | src/main/java/com/example/shortener/service/LinkService.java | Apply the per-instance per-remote-address rate limit before validation and code generation, calculate and persist expiration using the service clock, audit/log rejected attempts and expired accesses, and check expiration before incrementing clicks or redirecting. |
| modify | src/main/java/com/example/shortener/service/ShortUrlBuilder.java | Remove request-origin fallback behavior and construct short URLs exclusively from the validated configured public origin. |
| modify | src/main/java/com/example/shortener/service/UrlValidator.java | Fail configuration for absent or invalid public origins and canonicalize destination and configured-origin hosts using case folding, trailing-dot removal, and IDN ASCII conversion while preserving scheme and port checks. |
| create | src/main/java/com/example/shortener/service/CreationRateLimiter.java | Implement an atomic in-memory rolling one-minute window allowing 30 creation requests per client IP per application instance. |
| modify | src/main/java/com/example/shortener/service/AuditService.java | Use the service clock for auditable timestamps where required and support the new rate-limit and expired-access event types. |
| create | src/main/java/com/example/shortener/config/PublicOriginProperties.java | Bind and validate the required configured public origin at application startup rather than silently falling back to the request Host header. |
| create | src/main/java/com/example/shortener/config/TimeConfiguration.java | Provide an injectable service Clock so expiration timestamps and expiration comparisons are deterministic and testable. |
| modify | src/main/resources/application.yml | Make app.public-origin a required deployment configuration value while retaining environment-based configuration and document rate-limit/expiration settings if exposed as configuration. |
| modify | docs/api.md | Document expiresInDays, expiresAt, configured-origin URL behavior, HTTP 429 responses, and HTTP 410 expired-link responses. |
| modify | docs/operations.md | Document required public-origin configuration, per-instance remote-IP rate limiting, service-clock expiration, and audit/log event types. |
| modify | src/test/java/com/example/shortener/AnalyticsAndValidationIntegrationTest.java | Update creation setup for the required origin/rate limiter and add canonical host and configured-origin assertions without exhausting the shared per-IP limit. |
| modify | src/test/java/com/example/shortener/UrlShortenerApplicationTests.java | Supply a valid public-origin property and verify application startup/configuration behavior. |
| modify | src/test/java/com/example/shortener/UrlShortenerHttpIntegrationTest.java | Assert configured-origin short URLs, preserve destination redirects, and adjust creation fixtures for the new expiration field and per-IP limiter. |
| modify | src/test/java/com/example/shortener/controller/GlobalExceptionHandlerTest.java | Add assertions for HTTP 429 and HTTP 410 standard error responses. |
| modify | src/test/java/com/example/shortener/service/LinkServiceTest.java | Cover expiration calculation, pre/post-expiration redirect behavior, no click increment on expiration, rate-limit ordering, and injectable-clock behavior. |
| modify | src/test/java/com/example/shortener/service/ShortCodeAndUrlBuilderTest.java | Replace legacy request-origin expectations with configured-origin-only behavior and test differing Host headers. |
| modify | src/test/java/com/example/shortener/service/UrlValidatorTest.java | Cover case, trailing-dot, Unicode/IDN and punycode equivalent self-origin hosts plus invalid/missing origin configuration. |
| modify | src/test/java/com/example/shortener/service/AuditServiceTest.java | Cover timestamp injection and audit persistence for rate-limit and expired-link events. |
| create | src/test/java/com/example/shortener/service/CreationRateLimiterTest.java | Test 30-request rolling-window enforcement, independent IP counters, boundary expiry, and concurrent atomicity. |
| create | src/test/java/com/example/shortener/ShortLinkSafetyIntegrationTest.java | Exercise end-to-end expiresInDays validation, default/custom persistence, HTTP 410 behavior, audit/log observability, HTTP 429 behavior, and no link/code-generation consumption after throttling. |

## Impacted APIs

- POST /api/links: Uses the configured public origin for shortUrl, rejects self-origin destinations after canonical host comparison, accepts optional integer expiresInDays from 1 through 365, defaults expiration to 90 days, and returns HTTP 429 with the existing standard error body after 30 requests from the same remote IP in a rolling minute. The response may additionally include expiresAt. (backward compatible: "Partially. Existing valid requests remain valid and existing fields remain present, but rate limits, required valid public-origin configuration, expiration, and new validation failures change behavior.")
- GET /{code}: Valid non-expired links retain the existing 302 redirect to the stored destination. Expired newly created links return HTTP 410 with the standard error body and no Location header; pre-change links with null expiration remain usable. (backward compatible: "Partially. Existing valid and missing-link behavior is preserved, while expired links intentionally receive a new response.")
- GET /api/links/{code}: The details response includes the effective expiresAt when present; pre-change links can omit it. (backward compatible: "Yes, as an additive response-field change.")
- GET /api/links/{code}/analytics: No intentional contract or behavior change. (backward compatible: "Yes.")
- Application startup/configuration: A missing or invalid configured public origin fails startup or configuration binding instead of permitting request-Host-derived public URLs. (backward compatible: "No for deployments lacking a valid public-origin configuration; validly configured deployments remain compatible.")

## Data changes

- Add nullable links.expires_at mapped to Link.expiresAt. Nullable is required so links created before this change never expire and remain compatible with existing rows.
- Newly created links persist expiresAt equal to creation time plus 90 days by default or the requested 1-365 day duration.
- No new rate-limit persistence is introduced; counters are in memory and scoped to one application instance per the binding decision.
- Reuse audit_events for rate-limit rejection and expired-link access events; no separate audit table or retention policy is required.
- Existing ddl-auto:update can add the nullable column in the current deployment, but production schema management must ensure the column is added before code relying on it is deployed.

## Risks

- Making app.public-origin mandatory can prevent existing deployments or tests from starting until configuration is supplied.
- The in-memory limiter is not shared across instances, so a horizontally scaled deployment can exceed an aggregate global limit; this is explicitly accepted by the binding decision.
- Remote-address rate limiting can group many users behind one NAT/proxy and is vulnerable to direct-IP churn; forwarded headers are intentionally not trusted.
- Existing links require nullable expiration handling; accidentally treating null as expired would break all pre-change links.
- Expiration must be checked before click increments and redirect-event writes, otherwise expired accesses would pollute analytics.
- Concurrent limiter implementation errors could allow more than 30 requests or incorrectly reject requests near the rolling-window boundary.
- Changing LinkResponse serialization can affect strict clients or snapshot tests even though the new field is additive.
- Clock or timezone inconsistencies could produce incorrect boundary behavior unless expiration comparisons use Instant consistently.
- The current schema uses Hibernate update semantics; relying on automatic DDL in production may make rollout and rollback less predictable.
- Audit persistence failures on rejection paths could alter the intended HTTP response if audit recording remains fail-fast.

