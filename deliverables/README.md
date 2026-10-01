# SDLC deliverables

Every artifact the agents produced for the three scenarios, exported by the orchestrator (`scripts/sdlc export`).
Each scenario folder has one sub-folder per agent. QA figures come from a fresh `clean test` build of the released code.

The generated service, with its full agent-written commit history, is in its own repository:
**https://github.com/gurminderkhalsa-blip/url-shortener** (private).

| Scenario | Stories / criteria | Commits | Files reviewed | Issues found / resolved | Tests (failed) | Functional coverage | Line / branch coverage |
|---|---|---|---|---|---|---|---|
| [greenfield](greenfield/README.md) | 6 / 18 | 19 | 24 of 24 changed | 12 / 12 | 29 (0) | 18/18 criteria | 94.7% / 86.2% (target 100%) |
| [brownfield](brownfield/README.md) | 6 / 19 | 7 | 17 of 17 changed | 20 / 20 | 40 (0) | 19/19 criteria | 92.1% / 68.7% (target 100%) |
| [ambiguous](ambiguous/README.md) | 5 / 15 | 7 | 18 of 18 changed | 18 / 18 | 54 (0) | 15/15 criteria | 93.7% / 81.3% (target 100%) |

## Where each required artifact is

| Required | Location in each scenario folder |
|---|---|
| User stories and acceptance criteria (Requirements agent) | `01-requirements/user-stories.md`, `requirements-spec.md` |
| Design document and diagrams (Design agent) | `02-design/design.md` (Mermaid component, sequence and data-model diagrams), `api-contract.md`, `task-plan.md`, `impact-analysis.md` |
| Error handling, logging, auditing (Development agent) | `03-development/logging-audit-error-handling.md`, service docs in `03-development/documentation/` |
| Meaningful commits and history (Development agent) | `03-development/commit-history.md`, and the url-shortener repository |
| Code review: all code reviewed, issues and resolutions (Review agent) | `04-code-review/code-review-report.md` (every changed file, findings with dispositions, issue log) |
| Unit tests and coverage report (QA agent) | `05-qa/test-report.md`, `unit-coverage.md`, `jacoco-html/index.html` |
| Functional coverage, 100% target, gaps (QA agent) | `05-qa/functional-coverage.md` (every criterion to its tests), `coverage-gaps.md` (every uncovered line and why) |
| Release and governance | `06-release/`, `governance/audit-trail.md`, `human-decisions.md`, `metrics.md` |
