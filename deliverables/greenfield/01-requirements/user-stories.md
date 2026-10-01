# User stories and acceptance criteria

Produced by the Requirements agent and approved by a human at the requirements checkpoint.

## User stories

| ID | As a | I want | So that | Acceptance criteria |
|---|---|---|---|---|
| US1 | client | to submit a valid HTTP or HTTPS URL | I receive a short code and short URL that I can share | AC1, AC2, AC3, AC4 |
| US2 | client | to submit an invalid URL and receive a clear validation error | I know why the link was not created | AC5, AC6, AC7 |
| US3 | visitor | to visit a short-code path | I am redirected to the original URL | AC8, AC9, AC10 |
| US4 | client | to request details for a short code | I can inspect the original URL and its click count | AC11, AC12 |
| US5 | operator | key operations and errors to be logged | I can monitor and troubleshoot the service | AC13, AC14 |
| US6 | operator | link creation, rejected creation, redirect and unknown-code events persisted in an audit trail | I can review what happened and from which client | AC15, AC16, AC17, AC18 |

## Acceptance criteria

| ID | Criterion | User story |
|---|---|---|
| AC1 | POST /api/links with a valid absolute http or https URL returns HTTP 201 and a JSON response containing the original URL, a 7-character short code, a short URL ending in that code, and clickCount equal to 0. | US1 |
| AC2 | Two successful submissions of the same valid URL are both accepted and may return different codes; each returned code identifies a separately stored link. | US1 |
| AC3 | Across successful creations, generated codes contain only URL-safe characters and no two concurrently or sequentially created link records have the same code. | US1 |
| AC4 | A successful link creation is observable as a LINK_CREATED audit record containing a creation timestamp, generated code, client IP and successful outcome, and as an operational log entry. | US1 |
| AC5 | POST /api/links with a malformed URL, a relative URL, a missing URL, or a URL using a non-http(s) scheme returns HTTP 400 and a JSON error response with a clear validation message. | US2 |
| AC6 | A rejected creation does not create a link record and does not consume a successful short code. | US2 |
| AC7 | Every rejected creation creates a CREATION_REJECTED audit record with a timestamp, null or absent code, client IP and an outcome containing the validation reason, and writes an error or warning log entry. | US2 |
| AC8 | GET /{code} for a stored code returns HTTP 302 with a Location header equal to the stored original URL. | US3 |
| AC9 | Each successful GET /{code} redirect increases that link's persisted clickCount by exactly one, including when multiple redirect requests are made concurrently without lost updates. | US3 |
| AC10 | Each successful redirect creates a REDIRECTED audit record containing event time, code, client IP and successful outcome, and writes an operational log entry. | US3 |
| AC11 | GET /{code} for a code that does not exist returns HTTP 404 and does not return a Location header. | US4 |
| AC12 | GET /api/links/{code} for a known code returns HTTP 200 with the code, short URL, original URL and current clickCount; the same endpoint for an unknown code returns HTTP 404. | US4 |
| AC13 | An unknown-code request creates an UNKNOWN_CODE audit record containing event time, requested code, client IP and a not-found outcome, and writes an operational warning or error log entry. | US5 |
| AC14 | Service logs contain identifiable entries for link creation, rejected creation, successful redirect, unknown code and unexpected request-processing errors, with sufficient context to correlate the operation without exposing secrets. | US5 |
| AC15 | Audit records are stored through Spring Data JPA in the configured H2 database and can be observed through database state during application execution. | US6 |
| AC16 | Audit records include a non-null event timestamp, action, client IP and outcome for every required event; code is present for created, redirected and unknown-code events and is null or absent for rejected creations. | US6 |
| AC17 | The required audit actions are recorded exactly once per handled operation: LINK_CREATED for each successful creation, CREATION_REJECTED for each rejected creation, REDIRECTED for each successful redirect, and UNKNOWN_CODE for each unknown-code request. | US6 |
| AC18 | Unexpected server errors return an appropriate 5xx response and produce an error log entry; they do not expose stack traces, credentials or other secrets in the HTTP response. | US6 |

