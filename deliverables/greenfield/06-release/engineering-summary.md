## Plan and rationale
Implemented the approved greenfield design using Spring Boot, Spring Data JPA, and the existing H2 configuration. The implementation separates controllers, validation, link orchestration, code generation, URL construction, persistence, and audit services. A database uniqueness constraint plus bounded retries protects code uniqueness, while an atomic JPA update prevents lost click increments under concurrent redirects. Successful state-change audits join their operation transaction; rejection and unknown-code audits use `REQUIRES_NEW` so they remain persisted when the request returns an error.

## Artifacts produced
- JPA `Link` and `AuditEvent` entities.
- Spring Data repositories for links and audit events, including an atomic click-count increment query.
- URL validation, secure seven-character code generation, short URL construction, and bounded collision retry logic.
- API DTOs and controllers for link creation, details, and redirects.
- Centralized safe error handling for validation, not-found, code-generation exhaustion, and unexpected failures.
- Operational logging for required success and error paths.
- Persisted audit events with action, timestamp, code where applicable, client IP, and outcome.
- Unit and integration tests covering the acceptance criteria.

## Validation performed (tests, coverage, security review, gates)
- 29 tests passed with 0 failures, 0 errors, and 0 skipped tests.
- Reported line coverage is 94.7%, above the stated 90% minimum.
- All 18 acceptance criteria are covered; no uncovered criteria or phantom test cases were reported.
- Integration coverage includes duplicate creation, URL validation, code format and uniqueness, redirects, click counts, details, audit persistence, operational logs, unknown codes, and concurrent redirects.
- Unit coverage includes safe error mapping, audit transaction behavior, collision retries and exhaustion, malformed code handling, code generation, URL construction, and validation.
- Security review verdict was approved with no implementation findings. The review recorded four accepted medium risks relating to request-size limits, rate limiting, request-host-derived short URLs, and arbitrary HTTP(S) redirects.
- Code review verdict was approved with no findings.

## Risks and trade-offs
- H2 persistence is runtime-only, consistent with the requirement; links and audit events are not expected to survive restarts.
- Audit persistence failures propagate rather than being silently swallowed. This preserves the requirement that audited operations fail instead of reporting success without their required audit trail.
- Successful creation and redirect audits are transactionally coupled to their state changes; rejection and unknown-code audits are independent transactions so error handling does not erase the audit record.
- URL validation is syntactic and limited to absolute HTTP(S) URLs with an authority/host and a maximum length of 2048 characters. No network, malware, or reachability check is performed.
- Request-derived short URL origins are retained as an accepted interim behavior when no trusted public base URL is configured.

## Assumptions
- The approved public paths and fields are `POST /api/links`, `GET /api/links/{code}`, `GET /{code}`, and response fields `originalUrl`, `code`, `shortUrl`, and `clickCount`.
- Successful redirects use HTTP 302 to avoid implying permanent caching while allowing each visit to be counted.
- Repeated submissions create independent link records.
- Client IP is the servlet request remote address; forwarded headers are not trusted.
- Invalid short-code paths are audited as `UNKNOWN_CODE` using a sanitized/truncated code value and return the fixed not-found response.
- Rejected creation audits have no code and include the validation reason in the outcome.

## Limitations and follow-ups
- Add servlet/container request-size limits before deserialization.
- Add rate limiting and abuse monitoring for creation, lookup, redirect, and unknown-code traffic.
- Configure and validate a trusted production public base URL rather than relying on the request Host header.
- Consider destination-domain policy, abuse reporting, reputation checks, or authentication if the deployment threat model requires protection against open-redirect or phishing abuse.
- No additional follow-up implementation is required for the currently approved release scope.

## Rollback plan
The release is represented by the URL-shortener change set on the run branch. If rollback is required, revert the release commits on that branch in reverse order using normal Git revert procedures, resolve any resulting conflicts, and redeploy the reverted build. Because the service uses runtime H2 storage, rolling back code does not provide persistence recovery for data created during the released process lifetime.