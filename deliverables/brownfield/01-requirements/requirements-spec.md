# Requirements specification

Enhance the existing URL shortener with redirect analytics, self-link and destination-length validation, and logging/auditing for the new behaviors while preserving existing endpoint behavior.

**Change type:** brownfield

## Functional requirements

- Record one redirect event for each successful short-link redirect, including the event timestamp and the HTTP Referer header value when present.
- Persist redirect events in the database with a relationship to the redirected link.
- Provide GET /api/links/{code}/analytics for analytics belonging to the link identified by code.
- Analytics must include total clicks for the link, daily click counts for each day in the last 30 days, and the five most frequent non-empty referrers ordered by descending count.
- Analytics queries must perform counting, date-bounded grouping, ordering, and limiting in the database rather than loading all redirect events into application memory.
- Use the configured public origin from app.public-origin when determining whether a destination points to the shortener itself.
- If app.public-origin is not configured, determine the public origin from server-side application configuration only; never derive it from the incoming request Host header.
- Reject destinations that point to the configured public origin, including links whose path targets the shortener's own redirect route.
- Reject destination URLs whose character length exceeds 2048 using the existing invalid-URL error response and status behavior.
- Audit and log analytics requests, rejected self-links, and rejected over-long destinations.
- Preserve all existing endpoint routes, successful response bodies, error response contracts, and redirect behavior except where the new validation requirements intentionally reject a request.

## Non-functional requirements

- Analytics operations must remain database-backed and bounded: the top-referrer query must apply a database limit of five, and the daily query must apply a date range.
- Public-origin comparison must be normalized consistently for scheme, host, port, and URL parsing, while not treating an unrelated origin as self-referential.
- Redirect event timestamps must use a server-defined timezone and a consistent persisted time representation.
- Logging and audit records must contain enough contextual information to identify the operation and outcome without exposing credentials or secrets.
- The analytics endpoint must return the existing not-found behavior when the supplied short code does not identify a link.
- The implementation must use configuration or environment variables for any deployment-specific origin values and must not introduce hard-coded credentials or secrets.

## Out of scope

- Authentication, authorization, rate limiting, or tenant isolation for the analytics endpoint.
- Analytics filtering by arbitrary date ranges, user agents, IP addresses, geographic location, or device type.
- Changing the existing public API response formats or error messages for cases not covered by the new validation rules.
- Backfilling redirect analytics for redirects that occurred before the new redirect-event persistence is deployed.
- Changing the short-code generation algorithm or destination URL canonicalization beyond what is required for validation.
- Deleting, aggregating, or archiving historical redirect events.

## Open questions, answers and assumptions

| Question | Human answer | Assumption |
|---|---|---|
| What exact JSON shape should GET /api/links/{code}/analytics return? |  | Return an object containing totalClicks, clicksPerDay, and topReferrers. clicksPerDay is an array of 30 date/count objects, including zero-count days; topReferrers is an array of referrer/count objects. |
| What does 'last 30 days' mean at the boundaries? |  | Use the server's configured timezone and include the current calendar day plus the preceding 29 calendar days. The database range is inclusive of the start instant and exclusive of the next day after the current day. |
| How should redirects without a Referer header be represented in analytics? |  | Persist a null or empty value as no referrer and exclude it from topReferrers; such redirects still contribute to totalClicks and clicksPerDay. |
| How should public-origin fallback be configured when app.public-origin is absent? |  | Construct the fallback from server-side configuration such as configured scheme, bind address/public host, and port, with deployment configuration responsible for supplying a usable public host. The incoming Host header is never used. |
| Should self-link detection reject only exact public-origin URLs or any URL whose host resolves to the shortener? |  | Reject URLs with the configured public origin, including equivalent normalized scheme/host/port representations; DNS resolution and network-based alias detection are out of scope. |
| What audit detail is required for rejected URLs? |  | Record the event type, outcome, timestamp, reason, and safe request/link context; do not record credentials or secrets. The destination may be recorded according to the existing audit trail's current handling of URL data. |

