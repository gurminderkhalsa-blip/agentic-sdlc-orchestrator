# SDLC deliverables: brownfield scenario

All artifacts produced by the agents for run `7f93ea2c-5dc3-4112-a256-a1195bdd5160` (status SUCCEEDED), exported by the orchestrator. Requirement:

> Enhance the existing URL shortener:
> 1. Analytics: record each redirect's timestamp and referrer (Referer header, if any), and add GET /api/links/{code}/analytics that returns, for one link, total clicks, clicks per day for the last 30 days, and the top 5 referrers. Compute these with database queries (count, date-bounded grouping, ordering with a limit), not by loading all events into memory.
> 2. Bug fix: short links can currently be created for URLs pointing back at the shortener itself, which creates redirect loops. Reject them. The shortener's public origin comes from configuration (app.public-origin), falling back to the server's own configuration; never derive it from the request's Host header.
> 3. Reject destination URLs longer than 2048 characters with the existing invalid-URL error.
> 4. Log and audit the new behaviour (analytics requests, rejected self-links and over-long URLs) in the existing logging and audit trail.
> Existing endpoints and their responses must keep working unchanged.

## At a glance

| Artifact | Result |
|---|---|
| User stories / acceptance criteria | 6 / 19 |
| Commits on the run branch | 7 |
| Classes with logging / audit entities | 7 / [AuditEvent.java] |
| Files reviewed | 17 of 17 changed |
| Issues found / resolved | 20 / 20 |
| Tests (failed) | 40 (0) |
| Functional coverage | 19/19 criteria |
| Line / branch coverage | 92.1% / 68.7% (target 100%) |

## Contents

| Agent | Deliverables |
|---|---|
| Requirements | [user-stories.md](01-requirements/user-stories.md), [requirements-spec.md](01-requirements/requirements-spec.md) |
| Design / planning | [design.md](02-design/design.md) (with diagrams), [api-contract.md](02-design/api-contract.md), [task-plan.md](02-design/task-plan.md), [impact-analysis.md](02-design/impact-analysis.md) |
| Development | [commit-history.md](03-development/commit-history.md), [change-set.md](03-development/change-set.md), [logging-audit-error-handling.md](03-development/logging-audit-error-handling.md), [documentation/](03-development/documentation/) |
| Code review | [code-review-report.md](04-code-review/code-review-report.md) |
| QA | [test-report.md](05-qa/test-report.md), [functional-coverage.md](05-qa/functional-coverage.md), [unit-coverage.md](05-qa/unit-coverage.md), [coverage-gaps.md](05-qa/coverage-gaps.md), [jacoco-html/](05-qa/jacoco-html/index.html) |
| Release | [release-notes.md](06-release/release-notes.md), [engineering-summary.md](06-release/engineering-summary.md) |
| Governance | [audit-trail.md](governance/audit-trail.md), [human-decisions.md](governance/human-decisions.md), [metrics.md](governance/metrics.md) |

