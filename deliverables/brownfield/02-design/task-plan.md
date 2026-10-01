# Task plan

Produced by the Planner agent; the validTaskPlan gate checked it is a dependency graph without cycles.

| Task | Title | Depends on | Acceptance criteria |
|---|---|---|---|
| T1 | Add persisted redirect-event model and database-backed analytics queries |  | AC1, AC2, AC4, AC6, AC7, AC8 |
| T2 | Implement configured-origin and destination validation |  | AC9, AC10, AC11, AC12, AC13, AC19 |
| T3 | Record one redirect event for successful redirects | T1 | AC1, AC2, AC3, AC18 |
| T4 | Add analytics service and GET endpoint | T1 | AC4, AC5, AC6, AC7, AC8, AC18 |
| T5 | Log and audit analytics and rejected creation attempts | T2, T4 | AC14, AC15, AC16, AC17 |
| T6 | Add regression and feature coverage for analytics and validation | T3, T4, T5 | AC1, AC2, AC3, AC4, AC5, AC6, AC7, AC8, AC9, AC10, AC11, AC12, AC13, AC14, AC15, AC16, AC17, AC18, AC19 |

## Risks

- The existing persistence setup may not automatically create or migrate the RedirectEvent table in deployed environments. — mitigation: Inspect the current schema strategy before implementation; add the migration or entity-schema change required by that strategy and verify startup against a clean database and an existing database.
- Database date grouping semantics may vary by JPA provider or database timezone and produce incorrect calendar-day buckets. — mitigation: Persist timestamps in one documented representation, calculate boundaries in the configured server timezone, use provider-compatible grouping or a native query where necessary, and test around midnight and daylight-saving transitions.
- Adding event persistence to redirects could change redirect latency or cause successful redirects to fail when analytics storage is unavailable. — mitigation: Use the existing transaction/error semantics explicitly, add an index on link and timestamp, and test the intended failure behavior rather than silently swallowing persistence errors.
- Origin normalization can incorrectly classify URLs with equivalent textual forms or non-default ports. — mitigation: Parse URLs structurally, normalize scheme/host casing and default ports, compare configured origin and target origin separately from path, and add tests for ports, trailing paths, malformed URLs, and unrelated origins.
- Logging destination URLs or request data could expose sensitive information. — mitigation: Log only operation, code, rejection reason, outcome, and safe bounded context; follow the existing audit trail's URL handling and explicitly exclude credentials, authorization data, and secrets.
- The analytics endpoint may accidentally load all redirect events while assembling results. — mitigation: Expose repository methods that return scalar and grouped projections, inspect generated SQL/query plans in tests or review, and enforce database-side date predicates, ordering, grouping, and limit.

