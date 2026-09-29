# Agentic SDLC Orchestrator — Engineering Write-up

A Spring Boot service that takes a software requirement through the whole delivery lifecycle —
requirements, planning, impact analysis, design, implementation, tests, docs, security review, review and
release — using OpenAI-backed agents, under explicit governance. It built and then evolved a URL shortener
across three scenarios: greenfield, brownfield and ambiguous.

**Principle:** agents execute inside defined autonomy boundaries; humans own the approvals and the final
quality decision. Every boundary is enforced in code, not in a prompt.

---

## 1. What it is, in five sentences

1. A requirement enters through a REST API and becomes a *run* of a workflow defined as a dependency graph
   (`orchestrator/src/main/resources/workflows/llm/sdlc.yaml`).
2. The engine starts every stage whose dependencies are done — in parallel where the graph allows — and
   each stage is performed by one agent that makes one model call per attempt.
3. Nothing an agent produces is trusted: its output is checked by policies (security, change control) and by
   gates that run the real build and tests; only then is it committed, otherwise it is rolled back and the
   agent retries with the failure as feedback.
4. Humans approve the spec, the design, the release and any policy-flagged change; they can reject with a
   comment, send a finished stage back, stop a run, or edit an artifact — and every such decision becomes
   binding context for all later agents.
5. Every action is in an append-only audit trail, every output is versioned and content-hashed, code lives
   in git with one commit per stage attempt, and reliability metrics are computed from that durable state.

---

## 2. Architecture

```
                        ┌─────────────────────────────── Orchestrator (Spring Boot) ───────────────────────────────┐
  Human / reviewer ───▶ │ REST API ──▶ WorkflowEngine ──(virtual threads)──▶ NodeExecutor ──▶ Agent ──▶ LlmClient  │ ──▶ OpenAI
  (approve / reject /   │              │ per-run lock, scheduling,          │ entry gates        │      (live /      │
   rerun / stop / edit) │              │ approvals, publish, re-planning,   │ attempts: retry →  │      record /     │
                        │              │ safe-stop, budgets, crash recovery │ fallback, repair   │      replay)      │
                        │              ▼                                    │ policies           ▼                   │
                        │   H2 (JPA): runs, stages, attempts, artifacts,    │ exit gates     reads artifacts,        │
                        │   decisions, approvals, audit events              ▼                writes files            │
                        │                                       pass: git checkpoint + commit outputs             │
                        │                                       fail: restore files + discard outputs + feedback  │
                        └──────────────────────────────────────────────────────────────────────────────────────────┘

   workspace/target/url-shortener (main) ──clone──▶ workspace/runs/<runId> (branch run/<id>, 1 commit per attempt)
                  ▲                                                   │
                  └──────────── fast-forward only, on human release approval ────────────┘
```

### The workflow graph

```
 requirements* ─┬─▶ plan ───────────────┐
                └─▶ impact_analysis? ───┴─▶ design* ─▶ implement ─┬─▶ tests ───────────┐
                                                                  ├─▶ docs ────────────┼─▶ review ─▶ release_readiness*
                                                                  └─▶ security_review ─┘                 (publish)
 * human checkpoint     ? brownfield only (condition)     parallel branches join at review
```

### Components and responsibilities

| Component | Responsibility | Where |
|---|---|---|
| Workflow loader | Parses the YAML graph and refuses anything unsafe: cycles, unknown agents/gates/conditions, duplicate outputs, retries > 10, write scopes without a workspace | `workflow/WorkflowLoader` |
| Workflow engine | The only writer of run state (per-run lock). Starts ready stages, joins branches, handles approvals, publish, re-planning, stop/resume, crash recovery | `engine/WorkflowEngine` |
| Node executor | Runs one stage: entry gates → attempts (bounded retries → fallback) → policies → exit gates → commit or roll back | `engine/NodeExecutor` |
| Agents (10) | One job each; build a prompt from upstream artifacts, binding human decisions, repair base and feedback; one model call; parse JSON; apply | `agent/llm/*`, `resources/prompts/*.md` |
| LLM clients | Live OpenAI (JSON mode), record (live + save), replay (saved answers by stage and attempt) | `llm/*` |
| Workspace | Per-run git clone and branch; sandboxed file access; per-attempt sessions; checkpoint commits; scoped revert; fast-forward publish | `workspace/*` |
| Gates (13) | Entry/exit checks, including real builds: `compiles`, `existingTestsPass`, `testsPass`, `coverage`, `acceptanceCriteriaCovered`, `validTaskPlan`, `impactFilesExist`, `noBlockingSecurityFindings`, `reviewApproved`, … | `gate/*` |
| Policies (7) | Run before any build: `writeScope`, `protectedFile`, `schemaChange`, `dangerousCode`, `changeSize`, `secretScan`, `artifactSize` | `policy/rules/*` |
| Audit and metrics | Append-only audit events (DB + JSON log); success rate, retries, rollbacks, MTTR, latency | `audit/*`, `metrics/*` |

---

## 3. How each assignment requirement is met

| Requirement | Implementation | Evidence |
|---|---|---|
| Requirement understanding | Requirements agent normalises intent into a spec with functional/non-functional requirements, out-of-scope, open questions (assumed or blocking) and testable acceptance criteria | Ambiguous run: narrow reading corrected at the spec checkpoint |
| Task decomposition | Planner produces tasks with dependencies; `validTaskPlan` gate rejects cycles, unknown dependencies, single-task plans | Task plans in every run |
| Codebase reasoning (brownfield) | Impact analyst reads the real repository; `impactFilesExist` gate rejects invented paths | Brownfield: 8 and ambiguous: 15 existing files verified |
| Dependency graph with entry/exit gates | YAML DAG; engine starts a stage only when all dependencies are SUCCEEDED/SKIPPED; gates at both boundaries | `SchedulingTest`, `WorkflowLoaderTest` |
| Sequential and parallel paths with synchronisation | tests ∥ docs ∥ security_review, joined at review; a test proves branches run concurrently | `parallelBranchesRunConcurrentlyAndJoinWaitsForBoth` |
| Cross-stage context and decision lineage | Content-hashed, versioned artifacts; each stage records the input hashes it used; decisions stored with rationale; human decisions passed to agents as binding | `StageRun.inputHashes`, `Decision`, binding decisions in prompts |
| Human approval for high-impact actions | Checkpoints at spec, design, release; policy-driven approvals for build/config and schema changes; CLARIFICATION for ambiguity | 38 approvals across runs |
| Bounded retries, fallback, rollback, safe-stop | Retries ≤ 10 (validated), fallback agent, circuit breaker on repeated identical failures; file restore / git revert; stop, resume, budgets (time, attempts, tokens) | `RetryAndRecoveryTest`, `SafeStopTest`, `BudgetTest`; two real budget stops |
| Policy guardrails | Security (secrets, dangerous code), change control (write scopes, protected files, schema changes, change size) — evaluated before code is built | `PolicyRulesTest`; schema-change approval in the ambiguous run |
| Audit-grade observability | Append-only audit trail for every transition, finding, approval, rollback, checkpoint, publish; git history per attempt | `/api/runs/{id}/audit` |
| Reliability metrics | Success rate, first-pass rate, retry rate, rollbacks, MTTR, end-to-end latency, agent vs. human-wait time | `/api/metrics`, `/api/runs/{id}/metrics` |
| Dynamic re-planning | Editing an artifact or sending a stage back invalidates exactly its descendants (their outputs withdrawn, commits reverted), re-evaluates conditions, keeps independent branches | `GovernanceTest`, `WorkspaceWorkflowTest` |
| Engineering outputs | Production code, API contract, unit/integration tests, README and API docs, release notes and an engineering summary per run | Target repository `main` |
| Validation and risk control | Real compile/test/coverage gates, acceptance-criteria coverage, security review, reviewer at the join, accepted risks recorded explicitly | Release evidence below |
| Controlled autonomy | Agents change only their declared paths, on their own branch; `main` moves only by fast-forward after human release approval | Publish refuses if `main` moved |

---

## 4. The three scenarios

| | Greenfield (clean recording) | Brownfield | Ambiguous |
|---|---|---|---|
| Requirement | Build a URL shortener: create, redirect, click count, validation | Add click analytics; reject self-links (redirect loops); 2048-char limit | "Make the short links safer and more reliable for our users." |
| Result | Released: 15 classes, 22 tests, 94.1% coverage | Released: 39 tests, 88.5% coverage, 10/10 criteria | Released: 56 tests, 89.5% coverage, 12/12 criteria |
| Attempts / retries | 11 / 2 | 54 / 44 | 19 / 9 |
| Human checkpoints (rejected) | 4 (0) | 16 (3) | 6 (1) |
| Tokens | 129k | 1.06M | 389k |
| Replayable without a key | **Yes** (verified from a fresh clone) | Evidence only | Evidence only |

### Greenfield
The spec, plan and design passed first time; the test stage needed two retries; review and security passed.
The first greenfield run (kept as `greenfield-v1.json`) is where the stale-test-results and
repair-mode problems were found. A second attempt at a clean recording (`greenfield-v2-aborted.json`)
exposed a model that repeated the same wrong Spring Boot 3 import four times, which led to known-fix hints
and the circuit breaker. The third recording is the demo.

### Brownfield
Impact analysis found every affected file in the real code. Checkpoint decisions made by the human:
analytics path under `/api`; public origin from configuration, never from the `Host` header (a conscious
deviation from the spec, approved at design); two HIGH security findings sent back (URL length, in-memory
aggregation); `Referer` truncation ordered; `Host`-header short URL and trailing-dot hosts accepted as risks
and deferred. The tests then exposed two real production defects the agents had introduced: day grouping in
the host's time zone instead of UTC, and `day` used as an SQL alias (an H2 reserved word) — the second one
hidden at first by a test agent that mocked the repository. The first brownfield run was stopped after an
orchestrator bug (see incident 4) and kept as a record.

### Ambiguous
The requirements agent read "safer" narrowly (URL syntax and error handling). The human set the scope at the
spec checkpoint: configured public origin for short URLs, host canonicalisation, rate limiting (30/min/IP,
429), link expiry (90 days, 410), no URL scanning — which also closed the risks deferred from brownfield.
Two clarifications were added in an approval comment and became binding for all later agents (client IP =
remote address, never `X-Forwarded-For`; legacy links never expire). The **schema-change policy** fired for
the new `expires_at` column. The tests caught one spec violation (lenient number parsing); one rework fixed
it and every later stage passed first time.

---

## 5. Incident log — what the runs taught the orchestrator

Every incident below was found by a real run, fixed, and covered by a regression test.

| # | What happened | How it was detected | Fix |
|---|---|---|---|
| 1 | A stage after a skipped optional stage waited for the skipped stage's output | Day-1 end-to-end test | Skipped stages' outputs are not expected inputs |
| 2 | The test gate reported the *previous* attempt's results after a compile failure; the agent chased ghosts | Failure named a test that no longer existed | Delete old results first; report compile errors first |
| 3 | Each retry rewrote everything from scratch, fixing one mistake and making a new one | Four attempts, four different errors | Repair mode: previous attempt's files go back to the agent |
| 4 | Sending a stage back reverted commits **inherited from main** (the greenfield release) | Audit: "reverted 2 commits" where 1 was expected | Reverts scoped to commits after the run's baseline |
| 5 | `.gitignore` rule `workspace/` hid the Java package `…/workspace/`; GitHub had code that did not compile | Commit showed fewer files than changed | Rules anchored to the root; fresh-clone build check |
| 6 | A 34k-character test report overflowed a 4000-character column and crashed the stage | "Orchestrator error" in stage status | Stored reasons truncated; feedback capped |
| 7 | Code that compiled but could not start (missing `Clock` bean) passed implementation | Security review and 14 failing tests downstream | `existingTestsPass` regression gate on implementation |
| 8 | Agents saw "Failed to load ApplicationContext" without its cause and guessed for four attempts | Build logs vs. feedback | Innermost "Caused by" appended to feedback |
| 9 | Coverage passed while the whole analytics feature had no tests (agent dropped hard tests) | Test count lower than expected | `acceptanceCriteriaCovered` gate: every criterion ↔ a real test method |
| 10 | Reviewers re-raised risks the human had just accepted | Reviewer findings contradicted approval comments | Human decisions are binding context for every agent |
| 11 | Reworks lost earlier fixes: repair base replaced by partial attempts, not restored into the workspace, lost across re-runs | Each rework dropped a previously fixed item | Repair base persisted, merged, and restored into the workspace before the agent runs |
| 12 | A test agent mocked the repository in integration tests, hiding an SQL error behind green tests | End-to-end probe returned 500 | Mocks forbidden in `@SpringBootTest`; 500 is a defect; send-back feedback is binding |
| 13 | A model repeated the same wrong import four times | Identical failures in the attempt log | Known-fix hints in build feedback; circuit breaker after 3 identical failures |

The pattern behind most of these: **an agent will satisfy the check it is given, not the intent behind it.**
Each fix makes a check measure the intent more directly — real builds instead of claims, criteria mapped to
real test methods, the previous version restored instead of described.

---

## 6. Testing approach

- **Orchestrator: 61 fast tests** (no network, no builds; ~10 s) covering the loader, scheduling and
  parallelism, retries/fallback/circuit breaker, approvals, re-planning, safe-stop and budgets, policies,
  workspace git operations (checkpoint, restore, scoped revert, publish), record/replay, agents with a fake
  LLM, and a full dry-run of the SDLC graph through the REST API.
- **Slow test** (`./gradlew :orchestrator:slowTest`, ~20 s): real Gradle builds of the service template to
  prove the compile, test, coverage and regression gates report correctly (including stale results).
- **Generated service:** the gates *are* its test strategy — it must compile, keep existing tests green,
  pass its own tests with ≥ 70% line coverage, cover every acceptance criterion with a real test, and use
  the real database in integration tests. Each release was also rebuilt independently from `main`.
- **Replay:** the greenfield recording is replayed from a fresh clone with no API key; builds and tests run
  for real, only the model's text is canned. Result matched the recording exactly.

---

## 7. Key decisions and trade-offs

| Decision | Why | Cost |
|---|---|---|
| Own engine instead of LangGraph/CrewAI | Every rule is plain, testable Java that can be explained and defended | More code to own |
| One model call per attempt | Simple agents; exact record/replay; retries carry precise feedback | Very large changes must fit one context |
| Agents never run builds; gates do | An agent cannot claim tests pass | Slower feedback than letting agents iterate freely |
| Policies before gates | Generated code that spawns processes is never executed | Rules are pattern-based, not a full sandbox |
| Code in git, not artifacts | Rollback = restore or revert; history per attempt; fast-forward-only publish | Git operations need care (incident 4) |
| H2 + JPA state | Zero setup, durable across restarts, metrics re-derivable | Single node |
| Human decisions as binding context | Consistency across stages and reworks | Prompt grows with decisions |
| Replay keyed by stage and attempt | Deterministic demo without a key | Only valid while the orchestrator's retry behaviour is unchanged |

---

## 8. Limitations

- Generated code is built on the host (scrubbed environment, workspace-only directory, timeouts, and the
  dangerous-code policy first). Production use needs a container sandbox.
- Humans are identified by an `actor` field; there is no authentication or role-based approval.
- Single orchestrator instance; the run lock and stop signals are in memory.
- Publishing refuses if `main` moved; there is no automatic rebase.
- Only the greenfield recording replays cleanly; brownfield and ambiguous are kept as evidence.
- Open follow-ups in the generated service: parsed-host validation alongside IDN support, eviction for the
  rate limiter's per-IP buckets, a maximum request-body size, validation at the service boundary.
- Token cost was high while the orchestrator was being hardened (brownfield 1.06M); after the fixes the
  clean greenfield run used 129k.

---

## 9. Setup and demo

Requirements: Java 21. For live runs, `OPENAI_API_KEY` in the environment (never in a file).

```bash
./gradlew :orchestrator:test                       # 61 fast tests
./gradlew :orchestrator:bootRun                    # dry run with stub agents, no key
SDLC_LLM_MODE=replay ./gradlew :orchestrator:bootRun --args='--spring.profiles.active=llm'   # replay, no key
```

Replay the greenfield scenario (approve each checkpoint when the run pauses):

```bash
curl -s -X POST localhost:8080/api/runs -H 'Content-Type: application/json' -d @- <<'EOF'
{"requirement": "<requirement text from scenarios/greenfield.md>", "scenario": "GREENFIELD",
 "actor": "reviewer", "recording": "greenfield"}
EOF
curl -s localhost:8080/api/approvals                                        # pending checkpoints
curl -s -X POST localhost:8080/api/approvals/<id>/approve -H 'Content-Type: application/json' -d '{"actor":"reviewer"}'
curl -s localhost:8080/api/runs/<runId>/metrics
curl -s localhost:8080/api/runs/<runId>/artifacts/engineering_summary
```

The generated service ends up in `workspace/target/url-shortener` (`main`); each run's history is in
`workspace/runs/<runId>`.

---

## 10. Numbers across all runs

| Metric | Value |
|---|---|
| Runs | 6 (4 succeeded, 1 stopped by the human after incident 4, 1 abandoned after incident 13) |
| Stage executions passing on the first attempt | 84.9% |
| Attempts / rollbacks | 125 / 123 — every failed attempt was undone; nothing unapproved reached `main` |
| Human checkpoints | 38 across runs |
| Orchestrator code | ~150 source files, 22 test classes (61 fast tests + 1 slow), 14 prompt files |
