## Summary
Adds redirect analytics, self-link protection, destination-length validation, and operational auditing while preserving existing endpoint response contracts.

## User-visible changes
- Successful redirects now persist a timestamped redirect event and the optional `Referer` header.
- Added `GET /api/links/{code}/analytics` for existing links. The response contains:
  - `totalClicks`
  - `clicksPerDay`: exactly 30 calendar-day `{date, count}` entries, including zero-count days
  - `topReferrers`: up to five non-empty referrer/count entries, ordered by descending count with deterministic tie ordering
- Destinations longer than 2048 characters are rejected with the existing invalid-URL response and status.
- Destinations targeting the configured shortener origin and redirect URL space are rejected with the existing invalid-URL response and status.
- Analytics requests and the new rejected-link cases are logged and written to the existing audit trail.
- Referrer values are bounded to 2048 characters before persistence; blank or absent referrers are stored as absent and excluded from top-referrer results.

## API and compatibility
- Existing create-link, link-details, redirect, not-found, click-count, status-code, headers, and response-body behavior remains unchanged except for the intentional new validation failures.
- Analytics for an unknown or invalid code retains the existing not-found behavior.
- Analytics aggregation uses database count, date-bounded grouping, ordering, and a database-side limit of five rather than loading redirect events into application memory.

## Configuration and data
- `app.public-origin` configures the public origin used for self-link detection and generated short URLs.
- `app.time-zone` configures calendar-day boundaries; the default is `UTC`.
- A new redirect-event table and indexes are created through the existing JPA schema-update strategy. Existing redirects are not backfilled; analytics begins after deployment.

## Known issues and follow-ups
- When `app.public-origin` is explicitly empty, the current `ShortUrlBuilder` retains a legacy fallback that derives the generated short URL from the incoming request host. This is an accepted deferred risk, and deployments should provide a non-empty `APP_PUBLIC_ORIGIN`.
- The review evidence identifies a remaining hardening concern: `LinkAnalyticsService.safeCode()` truncates arbitrary invalid path input without validating the allowed code alphabet before logging/auditing. The review was marked `changes_requested`; this should be corrected and covered with a control-character/log-injection test before final approval.
- The analytics and redirect endpoints remain unauthenticated and have no application-level rate limiting; deployment-level controls are recommended.
- Analytics does not backfill events from historical redirects.