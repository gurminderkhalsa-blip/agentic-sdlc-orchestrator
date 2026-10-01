# User stories and acceptance criteria

Produced by the Requirements agent and approved by a human at the requirements checkpoint.

## User stories

| ID | As a | I want | So that | Acceptance criteria |
|---|---|---|---|---|
| US1 | short-link creator | new short links to use the configured public origin and reject destinations that resolve to that same origin | links are not dependent on an untrusted request Host header and self-referential destinations are prevented | AC1, AC2, AC3, AC4 |
| US2 | short-link creator | link creation to be rate-limited per client IP | automated or abusive creation cannot exhaust link-generation capacity for other users | AC5, AC6, AC7 |
| US3 | short-link creator | links to expire after a defined period with an optional creation-time override | links do not remain usable indefinitely | AC8, AC9, AC10 |
| US4 | short-link visitor | expired links to return a clear Gone response and valid links to redirect normally | I can distinguish an intentionally expired link from a missing link and continue using valid links | AC11, AC12, AC13 |
| US5 | service operator | rate-limited creation attempts and expired-link accesses to be logged and audited | abuse and expiration-related failures can be investigated | AC14, AC15 |

## Acceptance criteria

| ID | Criterion | User story |
|---|---|---|
| AC1 | Given a valid configured public origin and a successful link creation, the HTTP response's shortUrl is based on that configured origin, regardless of the request Host header. | US1 |
| AC2 | Given a redirect request, any generated Location header uses the configured public origin and never the request Host header; a test using a different Host header observes the same configured-origin result. | US1 |
| AC3 | Given a missing or invalid public-origin configuration, application startup or configuration binding fails with an observable configuration error instead of accepting requests that construct URLs from the request Host header. | US1 |
| AC4 | Given a destination whose host is equivalent to the configured public-origin host after case folding, trailing-dot removal, or IDN/punycode canonicalisation, link creation is rejected with the existing invalid-URL error response. | US1 |
| AC5 | For one client IP, the first 30 link-creation requests within a rolling one-minute window are processed under existing creation rules, while the next request within that window returns HTTP 429 with the standard ErrorResponse body. | US2 |
| AC6 | A request rejected with HTTP 429 does not create a database link, does not consume a short-code-generation attempt, and does not return a short URL. | US2 |
| AC7 | Requests from two distinct client IPs have independent rate-limit counters, and the configured trusted-proxy behavior determines whether a forwarded address is used. | US2 |
| AC8 | A link created without expiresInDays has a persisted expiration timestamp exactly 90 days after its creation time according to the service clock. | US3 |
| AC9 | A link created with expiresInDays values 1 and 365 persists expiration timestamps corresponding to those requested durations, while values 0, 366, negative values, non-integers, and malformed values are rejected with a validation error. | US3 |
| AC10 | Existing successful creation behavior and the existing HTTP 503 response for exhausted code generation remain observable for requests that are not rejected by rate limiting or expiration validation. | US3 |
| AC11 | A redirect request at or after a link's persisted expiration timestamp returns HTTP 410 with the standard ErrorResponse body and no Location header. | US4 |
| AC12 | A redirect request before a link's expiration timestamp retains the existing successful redirect status and behavior. | US4 |
| AC13 | A redirect request for a nonexistent short code retains the existing not-found status and standard error response. | US4 |
| AC14 | Each HTTP 429 rate-limited creation request creates an observable audit record and application log entry identifying a rate-limit rejection and its timestamp and client identifier. | US5 |
| AC15 | Each HTTP 410 expired-link access creates an observable audit record and application log entry identifying the expired-link access, short code or link identifier, timestamp, and expiration-related outcome. | US5 |

