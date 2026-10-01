# User stories and acceptance criteria

Produced by the Requirements agent and approved by a human at the requirements checkpoint.

## User stories

| ID | As a | I want | So that | Acceptance criteria |
|---|---|---|---|---|
| US1 | link owner | redirects to record their timestamp and referring URL | I can review how and when my short link is used | AC1, AC2, AC3 |
| US2 | link owner | to retrieve analytics for one short link | I can understand its traffic volume, recent daily activity, and leading referrers | AC4, AC5, AC6, AC7, AC8 |
| US3 | link creator | self-referential destination URLs to be rejected | creating a short link cannot create a redirect loop | AC9, AC10, AC11 |
| US4 | link creator | destination URLs longer than 2048 characters to be rejected using the existing invalid-URL behavior | oversized destinations are not stored or redirected | AC12, AC13 |
| US5 | operator | analytics requests and rejected link-creation attempts to be logged and audited | new behavior is traceable for operations and compliance | AC14, AC15, AC16, AC17 |
| US6 | consumer of the existing API | existing endpoints and response formats to remain unchanged | existing integrations continue to work | AC18, AC19 |

## Acceptance criteria

| ID | Criterion | User story |
|---|---|---|
| AC1 | When a valid short-link redirect succeeds, exactly one persisted redirect event is associated with that link and contains a timestamp. | US1 |
| AC2 | When a redirect request includes a Referer header, the persisted redirect event contains its value; when it does not, the event contains the configured no-referrer representation. | US1 |
| AC3 | A redirect event is persisted as part of the redirect operation such that a successful redirect cannot return a success response while silently omitting its event, subject to the application's existing transaction/error semantics. | US1 |
| AC4 | GET /api/links/{code}/analytics for an existing code returns HTTP 200 with totalClicks equal to the database count of redirect events for that link. | US2 |
| AC5 | The analytics response contains exactly 30 calendar-day buckets covering the configured last-30-day window, with each bucket containing its date and click count, including zero-count days. | US2 |
| AC6 | Daily click counts equal database counts grouped by calendar day within the defined 30-day date range; events outside that range are excluded from the daily buckets. | US2 |
| AC7 | The analytics response contains no more than five top-referrer entries, ordered by descending click count, with deterministic tie ordering, and excludes missing or blank referrers. | US2 |
| AC8 | The implementation's analytics data access performs total counting, date-bounded grouping, and top-referrer ordering/limit in database queries; it does not load all redirect events for a link into application memory. | US2 |
| AC9 | A destination whose normalized origin matches the configured app.public-origin and targets the shortener's own URL space is rejected during link creation before a Link is persisted. | US3 |
| AC10 | When app.public-origin is absent, self-link detection uses only the server's configured origin values and produces the same result independently of the incoming request Host header. | US3 |
| AC11 | A self-link creation attempt returns the existing invalid-URL error response and no short link is created. | US3 |
| AC12 | A destination with more than 2048 characters is rejected during link creation before persistence. | US4 |
| AC13 | An over-long destination returns the existing invalid-URL error response and no short link is created. | US4 |
| AC14 | Each analytics request produces an application log entry identifying the analytics operation, requested code, outcome, and request timestamp without credentials or secrets. | US5 |
| AC15 | Each analytics request produces an audit event identifying the operation, requested code, timestamp, and outcome, including not-found or error outcomes according to the existing audit model. | US5 |
| AC16 | Each rejected self-link attempt produces an application log entry and an audit event identifying self-link rejection and its outcome. | US5 |
| AC17 | Each rejected over-long destination attempt produces an application log entry and an audit event identifying length rejection and its outcome. | US5 |
| AC18 | Existing successful create-link, redirect, and any other pre-existing endpoint tests continue to receive the same routes, status codes, and response body structures. | US6 |
| AC19 | Existing invalid-URL cases not introduced by these requirements continue to receive the same error response contract, and the new self-link and over-length cases use that same contract. | US6 |

