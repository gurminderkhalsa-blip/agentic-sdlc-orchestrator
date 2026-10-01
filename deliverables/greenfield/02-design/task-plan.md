# Task plan

Produced by the Planner agent; the validTaskPlan gate checked it is a dependency graph without cycles.

| Task | Title | Depends on | Acceptance criteria |
|---|---|---|---|
| T1 | Create JPA link and audit persistence model |  | AC3, AC9, AC15, AC16 |
| T2 | Implement URL validation, secure code generation, and API contracts | T1 | AC1, AC3, AC5, AC6, AC11, AC12 |
| T3 | Implement transactional link and audit services | T1, T2 | AC2, AC4, AC6, AC7, AC9, AC10, AC13, AC15, AC16, AC17 |
| T4 | Expose HTTP endpoints and centralized error handling | T3 | AC1, AC5, AC7, AC8, AC11, AC12, AC18 |
| T5 | Add operational logging and configurable short URL construction | T3, T4 | AC4, AC7, AC10, AC13, AC14, AC18 |
| T6 | Add integration and concurrency coverage | T1, T2, T3, T4, T5 | AC1, AC2, AC3, AC4, AC5, AC6, AC7, AC8, AC9, AC10, AC11, AC12, AC13, AC14, AC15, AC16, AC17, AC18 |

## Risks

- A check-then-insert code-generation strategy can still collide under concurrent creation. — mitigation: Enforce a database unique constraint on code, catch duplicate-key persistence failures, and retry generation within a bounded loop.
- A read-modify-write click update can lose increments during concurrent redirects. — mitigation: Use a transactional atomic database update such as click_count = click_count + 1 and verify behavior with concurrent integration tests.
- Validation exceptions raised before normal service processing could omit the required rejected-creation audit event. — mitigation: Route request binding and validation failures through centralized handling that records CREATION_REJECTED with a null code and reason, while keeping audit persistence independent of link creation.
- Constructing short URLs from proxy-derived request headers could produce incorrect or attacker-controlled URLs. — mitigation: Use an explicitly configured public base URL when available and otherwise use the servlet request's direct scheme, host, port, and context path without trusting forwarded headers.
- Auditing in the same transaction as a link mutation could roll back the audit event when a persistence error occurs. — mitigation: Use a separate transaction for required audit writes where appropriate, and ensure duplicate-code retries do not emit successful creation audits until the link insert succeeds.
- Using a permissive URL parser may accept values that are syntactically valid but unsafe or unreachable. — mitigation: Enforce absolute HTTP/HTTPS scheme and authority presence only; do not perform network calls, consistent with the requirement's scope.

