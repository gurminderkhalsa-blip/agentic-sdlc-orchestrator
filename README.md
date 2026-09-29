# Agentic SDLC Orchestrator

A Spring Boot service that takes a software requirement through the SDLC — requirements, planning,
impact analysis, design, implementation, testing, docs, review, release readiness — using AI agents
under explicit governance: a dependency graph with entry/exit gates, parallel branches, human approval
checkpoints, bounded retries with fallback, rollback, safe-stop, policy guardrails, an audit trail and
reliability metrics.

The demo target is a URL shortener service.

## Status

All three scenarios (greenfield, brownfield, ambiguous) were run end to end and released. The greenfield
recording replays from a fresh clone with **no API key**. Full engineering write-up — architecture,
requirement mapping, scenario reports, incident log, testing approach, trade-offs and limitations:
**[docs/WRITEUP.md](docs/WRITEUP.md)**.

## Requirements

- Java 21 (Gradle finds it automatically)
- For live runs: an OpenAI API key in the `OPENAI_API_KEY` environment variable (never in a file)

## Run

```bash
./gradlew :orchestrator:test          # fast suite (no network, no builds)
./gradlew :orchestrator:slowTest      # real Gradle builds of the service template (~20s)
```

Dry run (stub agents, no key, no code written):

```bash
./gradlew :orchestrator:bootRun
```

Real run (OpenAI agents writing code into `workspace/`):

```bash
export OPENAI_API_KEY=...                       # in your shell profile, not in the repo
SDLC_LLM_MODE=record ./gradlew :orchestrator:bootRun --args='--spring.profiles.active=llm'
```

`SDLC_LLM_MODE` is `live`, `record` (live + save answers to `scenarios/recordings/`) or `replay` (saved
answers, no key). `SDLC_LLM_MODEL` overrides the model (default `gpt-5.6-luna`).

## Try it

```bash
# Start a run (recording is optional; used by record/replay)
curl -s -X POST localhost:8080/api/runs -H 'Content-Type: application/json' -d '{
  "requirement": "Build a URL shortener HTTP service ...", "scenario": "GREENFIELD",
  "actor": "alice", "recording": "greenfield"}'

curl -s localhost:8080/api/runs/<runId>                  # stages and pending approvals
curl -s localhost:8080/api/approvals                     # reviewer inbox
curl -s -X POST localhost:8080/api/approvals/<id>/approve -H 'Content-Type: application/json' -d '{"actor":"alice"}'
curl -s -X POST localhost:8080/api/approvals/<id>/reject  -H 'Content-Type: application/json' -d '{"actor":"alice","comment":"..."}'
curl -s -X POST localhost:8080/api/runs/<runId>/stages/implement/rerun -H 'Content-Type: application/json' \
  -d '{"actor":"alice","feedback":"handle duplicate codes"}'   # send a finished stage back
curl -s -X POST localhost:8080/api/runs/<runId>/stop -H 'Content-Type: application/json' -d '{"actor":"alice","reason":"..."}'
curl -s localhost:8080/api/runs/<runId>/artifacts/engineering_summary
curl -s localhost:8080/api/runs/<runId>/audit
curl -s localhost:8080/api/runs/<runId>/metrics
curl -s localhost:8080/api/metrics
```

The run's code is in `workspace/runs/<runId>` (branch `run/<id>`, one commit per stage attempt). After the
release approval it is fast-forwarded into `workspace/target/url-shortener` (`main`).

See [scenarios/](scenarios/README.md) for the greenfield, brownfield and ambiguous requirements.

## Architecture

```
REST API ──> WorkflowEngine ──(virtual threads)──> NodeExecutor ──> Agent ──> LlmClient (OpenAI / record / replay)
                │  per-run lock, scheduling,          │  entry gates              │
                │  approvals, publish, stop/resume,   │  attempts: retry→fallback │ reads artifacts, writes files
                │  re-planning, crash recovery        │  policies on files+output ▼
                ▼                                     │  exit gates (build, tests, coverage, content checks)
          H2 (JPA): runs, stages, attempts,           ▼
          artifacts, decisions, approvals,      pass: git checkpoint + commit outputs
          audit events                          fail: restore files + discard outputs, retry with feedback

  workspace/target/url-shortener (main)  ──clone──>  workspace/runs/<runId> (branch run/<id>)
                 ▲                                              │ one commit per stage attempt
                 └──── fast-forward only, on release approval ──┘
```

| Concern | Where |
|---|---|
| Dependency graph and validation (cycles, unknown refs, bounded retries) | `workflow/WorkflowLoader`, `resources/workflows/sdlc.yaml` |
| Scheduling, parallelism, joins | `engine/WorkflowEngine#advance` |
| Stage execution, retries, fallback, rollback of failed attempts | `engine/NodeExecutor` |
| Entry/exit gates | `gate/*` |
| Policy guardrails | `policy/PolicyEngine`, `policy/rules/*` |
| Human checkpoints | `WorkflowEngine#approve/reject`, `api/ApprovalController` |
| Re-planning when upstream outputs change | `WorkflowEngine#reviseArtifact` |
| Safe-stop and budgets | `WorkflowEngine#stop/resume`, `engine/BudgetGuard` |
| Lineage | `StageRun.inputHashes`, `Decision`, content-hashed `Artifact` versions |
| Audit and metrics | `audit/AuditService`, `metrics/MetricsService` |
| LLM agents and prompts | `agent/llm/*`, `resources/prompts/*.md` |
| Code workspace, sandbox, checkpoints, revert, publish | `workspace/*` |
| Build and quality gates | `gate/CompilesGate`, `TestsPassGate`, `CoverageGate`, `ValidTaskPlanGate`, `ImpactFilesExistGate`, `NoBlockingSecurityFindingsGate`, `ReviewApprovedGate` |
| Change-control and security policies | `policy/rules/WriteScopeRule`, `ProtectedFileRule`, `SchemaChangeRule`, `DangerousCodeRule`, `ChangeSizeRule`, `SecretScanRule` |

## Key design decisions

- **Own engine instead of a framework** — every rule (gates, retries, approvals, re-planning) is plain,
  testable Java that can be explained line by line.
- **Agents propose, the engine commits** — agent output is buffered and only committed after policies
  and exit gates pass, so a failed attempt never leaks into downstream stages.
- **Three gate outcomes** — `FAIL` is retried with feedback; `NEEDS_HUMAN` (e.g. an ambiguous
  requirement) goes straight to a person, because retrying cannot invent a business answer.
- **Metrics from durable state** — computed from the attempt, approval and audit tables, so they
  survive restarts and can be re-derived.
- **Stub mode** — canned agent outputs make the whole flow demoable and testable without an LLM.
- **One model call per attempt** — keeps agents simple and makes record/replay exact (keyed by stage and
  attempt). Retries carry the gate or policy failure as feedback, which is where most quality comes from.
- **Agents never see or run the build tools directly** — gates run Gradle, so an agent cannot claim tests
  pass. Policies run before any build, so generated code that tries to spawn processes never executes.
- **Code lives in git, not in artifacts** — each stage attempt is a commit on the run branch; rollback is a
  file restore (failed attempt) or `git revert` (rejected or re-planned stage). `main` only moves by
  fast-forward after the human release approval.
- **Scoped writes** — each stage declares which paths it may change (tests cannot edit production code).
- **Official OpenAI Java SDK, JSON mode** — no framework in between; the model is configuration.

## Known limitations

- Builds run generated code on the host (scrubbed environment, workspace-only directory, timeout, and
  the dangerous-code policy first). Production use should run builds in a container sandbox.
- Humans are identified by an `actor` field; there is no authentication.
- Publishing refuses if `main` moved since the run started; there is no automatic rebase.
- One model call per attempt limits very large changes to what fits in the context budget.
