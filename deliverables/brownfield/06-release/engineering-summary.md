## Plan and rationale
Implemented the additive brownfield design within the existing controller/service/repository architecture. Redirect events are persisted transactionally with successful redirects so a redirect cannot return success while silently omitting its required event. Analytics uses bounded database projections and constructs only the 30 zero-filled day buckets in application code. URL validation remains before link persistence and reuses the existing invalid-URL contract for self-links and over-long destinations.

## Artifacts produced
- `RedirectEvent` entity with link relationship, precise timestamp, configured-timezone calendar day, bounded referrer, and supporting indexes.
- Database-backed redirect-event repository queries for total counts, date-bounded daily grouping, and limited top-referrer aggregation.
- Analytics response records and `GET /api/links/{code}/analytics` endpoint.
- Configured-origin and maximum-length URL validation.
- Redirect event persistence and Referer capture in the redirect flow.
- Logging and audit integration for analytics outcomes and creation rejections.
- Configuration for `app.public-origin` and `app.time-zone`.
- Updated tests and API/operations documentation.

## Validation performed (tests, coverage, security review, gates)
- Test evidence reports 40 tests passing, with 0 failures, 0 errors, and 0 skipped tests.
- Reported line coverage is 92.1%, above the stated 90% minimum.
- All 19 acceptance criteria are reported as covered, with no uncovered criteria or phantom test cases.
- Integration and unit coverage includes redirect persistence, Referer handling, analytics totals and boundaries, zero-filled buckets, top-five behavior, self-link rejection independent of incoming Host, over-long URL rejection, audit/log behavior, not-found handling, and existing endpoint compatibility.
- Repository implementation evidence confirms database-side count, grouping, ordering, date bounds, and pagination/limit behavior.
- Security review identified medium-severity concerns involving invalid analytics-code sanitization, absent rate limiting, and the empty-origin request-host fallback. The review artifact verdict is `changes_requested`, despite later human direction stating the sanitization follow-up was addressed; the supplied implementation diff still shows truncation without allowed-alphabet validation, so this remains unresolved for sign-off.
- Runtime configuration and persisted-model changes were approved under the recorded implementation policy gates.

## Risks and trade-offs
- Redirect persistence adds database work and latency to every successful redirect; event persistence failure prevents a successful redirect by design.
- Persisting `eventDay` makes grouping deterministic across database timezone behavior, but changing the configured timezone changes future calendar-day classification.
- Referrers are truncated rather than causing a redirect failure, so an oversized Referer may appear as a truncated analytics value.
- Existing click counts remain independent of analytics event counts to preserve legacy response behavior.
- Unauthenticated analytics and redirect operations can be probed or abused without deployment-level rate limiting.
- The empty `app.public-origin` compatibility branch can permit Host-header influence when configuration is incomplete.

## Assumptions
- The existing JPA `ddl-auto: update` strategy is used, or an equivalent deployment migration creates the redirect-event table, foreign key, and indexes.
- The configured server timezone defines the current calendar day and the 30-day analytics window.
- Missing or blank Referer values are represented as null and excluded from top-referrer aggregation.
- Self-link detection compares normalized configured scheme, host, and effective port and applies only to the shortener redirect URL space.

## Limitations and follow-ups
- Correct `LinkAnalyticsService.safeCode()` so every analytics log and audit path accepts only `[A-Za-z0-9_-]{7}` and uses a fixed safe marker otherwise; add explicit control-character/log-injection coverage.
- Remove the request-host fallback or enforce a non-empty configured public origin at startup so the fallback is entirely server-configuration-based.
- Add rate limiting and monitoring at the reverse proxy, gateway, or application layer as an operational follow-up.
- Verify the additive schema change against each production database deployment strategy before rollout.

## Rollback plan
The change is represented by the commits on the run branch and can be reverted as a set. Revert the feature and follow-up commits in reverse dependency order, then deploy the resulting build. Preserve the redirect-event table during rollback unless data removal is explicitly required; older application versions can ignore the additive table. If schema rollback is required, perform it through the established database migration procedure after confirming no retained analytics data is needed.