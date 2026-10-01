# Task plan

Produced by the Planner agent; the validTaskPlan gate checked it is a dependency graph without cycles.

| Task | Title | Depends on | Acceptance criteria |
|---|---|---|---|
| T1 | Add public-origin, clock, and expiration configuration |  | AC3, AC8, AC9, AC10 |
| T2 | Implement configured-origin URL construction and canonical host validation | T1 | AC1, AC2, AC4 |
| T3 | Implement per-instance atomic creation rate limiting |  | AC5, AC6, AC7, AC14 |
| T4 | Integrate validation, rate limiting, and expiration into link creation | T1, T2, T3 | AC5, AC6, AC8, AC9, AC10 |
| T5 | Enforce expiration during redirects and audit expired accesses | T1, T2, T4 | AC2, AC11, AC12, AC13, AC15 |
| T6 | Add end-to-end regression and operational documentation | T4, T5 | AC1, AC2, AC3, AC4, AC5, AC6, AC7, AC8, AC9, AC10, AC11, AC12, AC13, AC14, AC15 |

## Risks

- The public-origin setting may conflict with an existing server URL or deployment configuration, causing startup failures after deployment. — mitigation: Resolve the documented precedence explicitly, validate one absolute origin at binding time, add a startup-failure test, and document the required deployment setting before rollout.
- In-memory rate limiting is not shared across application instances and may allow aggregate traffic above 30 requests per minute when load-balanced. — mitigation: Keep the explicitly approved per-instance behavior, document it operationally, and ensure each instance is concurrency-safe and independently observable.
- A naive rolling-window implementation can exceed the limit under concurrent requests or retain unbounded client state. — mitigation: Use atomic per-IP state updates, test concurrent boundary behavior, and periodically remove entries whose windows have elapsed.
- Changing the persisted Link schema may break existing records or incorrectly expire links created before the change. — mitigation: Make expiresAt nullable, migrate existing rows with null expiration, and define null as never expiring in both service logic and integration tests.
- Strict request deserialization may produce a different error shape for non-integer expiresInDays than existing validation errors. — mitigation: Route JSON parse and bean-validation failures through the existing GlobalExceptionHandler and assert the standard ErrorResponse for every invalid representation.
- IDN canonicalization libraries can differ in handling Unicode edge cases and trailing dots. — mitigation: Centralize canonicalization, use the platform IDN conversion consistently, normalize trailing dots before conversion, and test case, trailing-dot, Unicode, and punycode equivalents.
- Audit or application logs could expose full destination URLs or other unnecessary sensitive data. — mitigation: Log only event type, client identifier or short code, timestamp, and outcome; reuse the existing structured logging and audit abstractions.

