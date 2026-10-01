# SDLC deliverables: greenfield scenario

All artifacts produced by the agents for run `7dacc666-0e63-410b-a573-8345c12867f8` (status SUCCEEDED), exported by the orchestrator. Requirement:

> Build a URL shortener HTTP service.
> - Clients submit a long http(s) URL and get back a short code and short URL. Submitting the same URL twice may return a new code.
> - Visiting /{code} redirects to the original URL. Unknown codes return 404.
> - Each link records how many times it was followed; clients can fetch a link's details and click count.
> - Invalid URLs are rejected with a clear error. Codes must be unique, short (about 7 characters) and hard to guess.
> - Store links with Spring Data JPA in the H2 database already configured in the project (persistence across restarts is not required).
> - Operators can follow what the service does: key operations and errors are logged, and an audit trail in the database records link creation, rejected creations, redirects and unknown codes (time, action, code, client IP, outcome).

## At a glance

| Artifact | Result |
|---|---|
| User stories / acceptance criteria | 6 / 18 |
| Commits on the run branch | 19 |
| Classes with logging / audit entities | 6 / [AuditEvent.java] |
| Files reviewed | 24 of 24 changed |
| Issues found / resolved | 12 / 12 |
| Tests (failed) | 29 (0) |
| Functional coverage | 18/18 criteria |
| Line / branch coverage | 94.7% / 86.2% (target 100%) |

## Contents

| Agent | Deliverables |
|---|---|
| Requirements | [user-stories.md](01-requirements/user-stories.md), [requirements-spec.md](01-requirements/requirements-spec.md) |
| Design / planning | [design.md](02-design/design.md) (with diagrams), [api-contract.md](02-design/api-contract.md), [task-plan.md](02-design/task-plan.md) |
| Development | [commit-history.md](03-development/commit-history.md), [change-set.md](03-development/change-set.md), [logging-audit-error-handling.md](03-development/logging-audit-error-handling.md), [documentation/](03-development/documentation/) |
| Code review | [code-review-report.md](04-code-review/code-review-report.md) |
| QA | [test-report.md](05-qa/test-report.md), [functional-coverage.md](05-qa/functional-coverage.md), [unit-coverage.md](05-qa/unit-coverage.md), [coverage-gaps.md](05-qa/coverage-gaps.md), [jacoco-html/](05-qa/jacoco-html/index.html) |
| Release | [release-notes.md](06-release/release-notes.md), [engineering-summary.md](06-release/engineering-summary.md) |
| Governance | [audit-trail.md](governance/audit-trail.md), [human-decisions.md](governance/human-decisions.md), [metrics.md](governance/metrics.md) |

