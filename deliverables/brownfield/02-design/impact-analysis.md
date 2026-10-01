# Impact analysis (brownfield)

Produced by the Impact Analyst agent from the real repository; the impactFilesExist gate verified every file it names.

## Impacted files

| Change | File | Reason |
|---|---|---|
| modify | src/main/java/com/example/shortener/domain/Link.java | Add the JPA relationship needed for persisted redirect analytics events while preserving the existing link fields and click-count response behavior. |
| modify | src/main/java/com/example/shortener/domain/AuditEvent.java | Support any additional audit context or action values required for analytics requests and distinguished self-link and URL-length rejections without exposing sensitive data. |
| create | src/main/java/com/example/shortener/domain/RedirectEvent.java | Persist one timestamped redirect event per successful redirect, including the optional Referer value and its associated link. |
| create | src/main/java/com/example/shortener/api/AnalyticsResponse.java | Define the new analytics response containing totalClicks, 30 daily buckets, and up to five top referrers. |
| create | src/main/java/com/example/shortener/api/DailyClickCount.java | Represent each calendar-day click bucket, including days with zero clicks. |
| create | src/main/java/com/example/shortener/api/ReferrerClickCount.java | Represent each non-empty referrer and its database-calculated click count. |
| create | src/main/java/com/example/shortener/repository/RedirectEventRepository.java | Provide database-backed count, date-bounded grouped daily counts, and ordered limited top-referrer queries without loading all redirect events. |
| modify | src/main/java/com/example/shortener/repository/LinkRepository.java | Expose the link lookup and update operations needed to associate redirect events and preserve existing click-count behavior, if repository query support is required. |
| create | src/main/java/com/example/shortener/service/AnalyticsService.java | Coordinate link lookup, database-backed analytics queries, zero-filled 30-day bucket construction, configured-timezone boundaries, and analytics audit/log outcomes. |
| modify | src/main/java/com/example/shortener/service/LinkService.java | Persist exactly one redirect event during successful redirect processing, capture Referer and timestamp, invoke analytics support as needed, and apply self-link validation before link persistence. |
| modify | src/main/java/com/example/shortener/service/UrlValidator.java | Reject destinations longer than 2048 characters using the existing invalid-URL exception and reject normalized destinations targeting the configured public origin and redirect URL space. |
| modify | src/main/java/com/example/shortener/service/ShortUrlBuilder.java | Use the configured public origin for generated short URLs and provide a server-configuration fallback without deriving the origin from the incoming Host header. |
| modify | src/main/java/com/example/shortener/controller/LinkController.java | Expose GET /api/links/{code}/analytics and pass request context required for logging, auditing, and not-found behavior. |
| modify | src/main/java/com/example/shortener/controller/RedirectController.java | Preserve the existing redirect response while supplying the Referer-bearing request to redirect event persistence through the service. |
| modify | src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java | Keep the existing invalid-URL error contract while recording distinguishable audit and log outcomes for self-link and over-length rejections and analytics failures where applicable. |
| modify | src/main/java/com/example/shortener/exception/InvalidUrlException.java | Carry a safe rejection reason so self-link and length validation can be audited and logged distinctly without changing the client-facing error detail. |
| modify | src/main/resources/application.yml | Define the app.public-origin and server-side origin/timezone configuration used for self-link detection, short URL construction, and calendar-day analytics boundaries. |
| modify | src/test/java/com/example/shortener/UrlShortenerHttpIntegrationTest.java | Verify redirect event persistence and Referer capture, analytics response and audit/log behavior, self-link rejection, over-length rejection, and preservation of existing endpoint responses. |
| modify | src/test/java/com/example/shortener/controller/GlobalExceptionHandlerTest.java | Verify the unchanged invalid-URL response contract and the new safe audit/log classifications. |
| modify | src/test/java/com/example/shortener/service/LinkServiceTest.java | Mock redirect event persistence and request Referer handling, and cover validation/audit behavior without breaking existing create, resolve, collision, and not-found tests. |
| modify | src/test/java/com/example/shortener/service/UrlValidatorTest.java | Add configured-origin normalization and self-link cases while retaining existing malformed, unsupported, and over-long URL assertions. |
| modify | src/test/java/com/example/shortener/service/ShortCodeAndUrlBuilderTest.java | Verify configured public-origin behavior and server-configuration fallback without relying on request Host-derived origin values. |
| create | src/test/java/com/example/shortener/service/AnalyticsServiceTest.java | Unit test database projection mapping, 30-day zero-filled buckets, timezone boundaries, top-five limiting assumptions, and analytics audit outcomes. |
| create | src/test/java/com/example/shortener/repository/RedirectEventRepositoryTest.java | Verify database count, date-bounded grouping, non-empty-referrer filtering, deterministic ordering, and database-side limit behavior. |
| modify | docs/api.md | Document the new analytics endpoint and response shape while explicitly retaining existing endpoint contracts. |
| modify | docs/operations.md | Document public-origin configuration, timezone configuration, redirect-event persistence, and audit/log events for analytics and rejected creations. |

## Impacted APIs

- GET /api/links/{code}/analytics: New endpoint returning HTTP 200 for an existing code with totalClicks, exactly 30 calendar-day click buckets, and at most five ordered non-empty referrers; it retains the existing 404 not-found behavior. (backward compatible: true)
- POST /api/links: Existing successful response and ordinary invalid-URL response remain unchanged, but destinations longer than 2048 characters and destinations targeting the configured shortener origin and redirect path are rejected with the existing invalid-URL status and detail. (backward compatible: true)
- GET /{code}: Existing 302 redirect and Location response remain unchanged; each successful redirect additionally persists a timestamped event and optional Referer. (backward compatible: true)
- GET /api/links/{code}: No response contract change; clickCount remains the existing link counter and continues to represent successful redirects. (backward compatible: true)

## Data changes

- entity: RedirectEvent; change: Add a new persisted table/entity with an identifier, link relationship, event timestamp, and nullable or blank-safe referrer column.; migrationImpact: Requires schema creation in deployed environments. Existing links and historical redirects are not backfilled; analytics begins at deployment.
- entity: Link; change: Add the relationship metadata needed to associate redirect events with links, while retaining originalUrl, clickCount, and existing constraints.; migrationImpact: Relationship is additive and compatible with existing link rows.
- entity: AuditEvent; change: Reuse the existing audit trail for analytics requests and classified creation rejections; only widen or adjust constraints if required by the selected action/outcome values.; migrationImpact: Normally no data migration is required; existing audit rows remain readable.
- entity: Analytics query projections; change: Add database query projections for total counts, date-bounded daily grouping, and top-five referrer aggregation.; migrationImpact: No externally visible schema contract; query date grouping must use the configured server timezone consistently with persisted timestamps.

## Risks

- Adding redirect-event persistence to the redirect transaction can cause a previously successful redirect to fail if event persistence fails; this is required to avoid silently returning success without analytics.
- Concurrent redirects must continue to update clickCount safely while independently inserting one event per redirect; incorrect transaction boundaries could lose events or counts.
- Database-specific date truncation or grouping functions may behave differently across H2 and production databases, especially around timezone conversion and calendar-day boundaries.
- Origin normalization errors involving default ports, case, trailing slashes, encoded paths, or IPv6 literals could either permit a redirect loop or reject an unrelated destination.
- A missing or unusable configured public origin could make self-link validation ineffective or make generated short URLs unusable; deployment configuration must supply a server-side fallback host/origin.
- Analytics query projections and referrer columns can become performance hotspots without suitable indexes on link foreign key, event timestamp, and referrer.
- Existing tests or clients that assume clickCount is the sole redirect persistence mechanism may observe timing or transaction changes even though the response shape remains unchanged.
- Analytics output depends on the configured server timezone; changing that configuration changes day bucket boundaries but is necessary for consistent calendar-day semantics.

