# Agentic SDLC Orchestrator — Engineering Write-up

A Spring Boot service that takes a software requirement through the whole delivery lifecycle —
requirements, planning, impact analysis, design, implementation, tests, docs, security review, review and
release — using OpenAI-backed agents, under explicit governance. It built and then evolved a URL shortener
across three scenarios: greenfield, brownfield and ambiguous.

**Principle:** agents execute inside defined autonomy boundaries; humans own the approvals and the final
quality decision. Every boundary is enforced in code, not in a prompt.

**Where to look:**
- All SDLC artifacts, per scenario and per agent: [`deliverables/`](../deliverables/README.md)
- The generated service and its agent-written commit history: https://github.com/gurminderkhalsa-blip/url-shortener (private)

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
| Gates (17) | Entry/exit checks, including real builds and artifact checks: `userStoriesComplete`, `designDiagrams`, `compiles`, `existingTestsPass`, `loggingAndAuditing`, `testsPass`, `coverage`, `acceptanceCriteriaCovered`, `allFilesReviewed`, `validTaskPlan`, `impactFilesExist`, `noBlockingSecurityFindings`, `reviewApproved`, … | `gate/*` |
| Policies (8) | Run before any build: `writeScope`, `protectedFile`, `schemaChange`, `testRemoval`, `dangerousCode`, `changeSize`, `secretScan`, `artifactSize` | `policy/rules/*` |
| Deliverables exporter | Writes every artifact of a run into `deliverables/<scenario>/`, one folder per agent, with QA figures from a fresh build | `deliverables/*` |
| Audit and metrics | Append-only audit events (DB + JSON log); success rate, retries, rollbacks, MTTR, latency | `audit/*`, `metrics/*` |

---

## 3. How each assignment requirement is met

| Requirement | Implementation | Evidence |
|---|---|---|
| Requirement understanding | Requirements agent normalises intent into a spec with **user stories**, functional/non-functional requirements, out-of-scope, open questions (assumed or blocking) and testable acceptance criteria; `userStoriesComplete` gate | Ambiguous run: scope set at the spec checkpoint |
| Design documents and diagrams | Architect agent writes the design with Mermaid component, sequence and data-model diagrams, plus the API contract; `designDiagrams` gate | `deliverables/*/02-design/` |
| Error handling, logging, auditing | Implementer must log through SLF4J in every controller/service and keep an application audit trail; `loggingAndAuditing` gate; requirements carry operator stories for both | `deliverables/*/03-development/logging-audit-error-handling.md` |
| Meaningful commits | Each accepted attempt is a commit with the agent's Conventional Commit message and Stage/Attempt/Agent/Run trailers | url-shortener repository (34 commits) |
| Code review of all code, issues and resolutions | Reviewer sees every changed file and must list each (`allFilesReviewed` gate); the exporter builds an issue log from every failed attempt, rejection and send-back with its resolution | `deliverables/*/04-code-review/` |
| Unit + functional coverage, 100% target, gaps | 100% target, 90% hard floor, every acceptance criterion mapped to a passing test; JaCoCo report, functional coverage matrix, and a coverage-gaps report of every uncovered line | `deliverables/*/05-qa/` |
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

Final, artifact-complete runs (all deliverables in [`deliverables/`](../deliverables/README.md)):

| | Greenfield | Brownfield | Ambiguous |
|---|---|---|---|
| Requirement | URL shortener with logging and an audit trail | Click analytics; reject self-links; 2048-char limit | "Make the short links safer and more reliable for our users." |
| User stories / acceptance criteria | 6 / 18 | 6 / 19 | 5 / 15 |
| Tests / functional coverage | 29 / 18 of 18 | 40 / 19 of 19 | 54 / 15 of 15 |
| Line / branch coverage (target 100%) | 94.7% / 86.2% | 92.1% / 68.7% | 93.7% / 81.3% |
| Files reviewed | 24 of 24 | 17 of 17 | 18 of 18 |
| Issues found / resolved | 12 / 12 | 20 / 20 | 18 / 18 |
| Attempts / human checkpoints | 31 / 6 | 32 / 7 | 29 / 7 |
| Tokens | 695k | 952k | 954k |

### Greenfield
All artifact gates passed first time (6 stories, 4 diagrams, logging and audit, 24 files reviewed). The reviewer
then blocked on real defects: audit writes silently swallowed, no URL length limit, an unvalidated code path,
500 instead of the documented 503. Two reworks followed, and the second exposed a subtle interaction: making the
audit write join the request's transaction meant **failure events were rolled back with the failed request** —
the reviewer flagged it, an end-to-end probe confirmed zero audit rows after a 404, and the final rule became
"success events share the action's transaction; failure events get their own".

### Brownfield
Impact analysis worked from the real code. The run exposed an orchestrator deadlock: the requirement
intentionally changed audited behaviour, existing greenfield unit tests asserted the old internals, the
regression gate required them to pass unchanged, and implementation was not allowed to touch tests. The fix lets
implementation update affected tests, guarded by the new `testRemoval` policy. Security then caught an
oversized `Referer` header able to break redirects; a host-header behaviour from greenfield was re-accepted as a
deferred risk; the reviewer's last MEDIUM was recorded as a follow-up.

### Ambiguous
The agent read "safer" differently from the previous run (it proposed private-network blocking and idempotency
keys). The human set the scope at the spec checkpoint and added two binding clarifications. The tests then found
two real defects (self-links to short-code paths not rejected when the public origin has an empty path; lenient
number parsing). One failing test briefly disappeared between test attempts, and a later failure turned out to be
caused by an imprecise human instruction ("default-port variants"), corrected with a tests-only re-run. The
reviewer's final finding was a conflict between the agent's wording of AC3 and the human's approved scope,
resolved by a recorded human decision.

### Earlier runs
The first set of runs (recordings `greenfield-v1`, `brownfield-incident`, `brownfield-v1`, `ambiguous-v1`,
`greenfield-v2-aborted`, `greenfield-v3`) produced the orchestrator fixes in the incident log; they are kept as
evidence.

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

| 14 | Intended behaviour changes deadlocked the regression gate: implementation could not update the tests that asserted the old behaviour | Brownfield implementation failed 12 attempts | Implementation may update affected tests; `testRemoval` policy requires approval for removing tests |
| 15 | Implementation was asked to update tests it could not see (only `src/main` in its prompt) | Same stuck failure after the policy change | Test sources added to the implementer's context |
| 16 | In repair mode the test agent listed only its new test cases, so the coverage gate saw most criteria as uncovered | Gate evidence: 13 criteria "uncovered" at once | Prompt: always return the complete test case list |
| 17 | The issue log missed review-driven send-backs, the most important review evidence | Deliverables review | Send-backs (with the findings) and their resolving attempt added to the issue log |
| 18 | Two correct-sounding fixes combined into a new bug (failure audits rolled back with the failed request) | Reviewer finding, confirmed by a direct database probe | Explicit audit transaction rule; tests must check audit rows after 4xx responses |
| 19 | An imprecise human instruction ("default-port variants") became binding and made a test assert the wrong thing | Probe showed the implementation was right | Corrected with a tests-only re-run; lesson: governance feedback must be precise |

The pattern behind most of these: **an agent will satisfy the check it is given, not the intent behind it.**
Each fix makes a check measure the intent more directly — real builds instead of claims, criteria mapped to
real test methods, the previous version restored instead of described.

---

## 6. Testing approach

- **Orchestrator: 71 fast tests** (no network, no builds; ~10 s) covering the loader, scheduling and
  parallelism, retries/fallback/circuit breaker, approvals, re-planning, safe-stop and budgets, policies,
  workspace git operations (checkpoint, restore, scoped revert, publish), record/replay, agents with a fake
  LLM, and a full dry-run of the SDLC graph through the REST API.
- **Slow test** (`./gradlew :orchestrator:slowTest`, ~20 s): real Gradle builds of the service template to
  prove the compile, test, coverage and regression gates report correctly (including stale results).
- **Generated service:** the gates *are* its test strategy — it must compile, keep existing tests green,
  pass its own tests with ≥ 90% line coverage (target 100%, gaps itemised), cover every acceptance criterion
  with a real test, and use the real database in integration tests. The exporter rebuilds every release from
  scratch to produce the published reports, and key behaviours were also checked with direct end-to-end probes.
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

## 8. AI mindset — how AI was used, and how it was governed

- **AI as the workforce, humans as the accountable owners.** Ten agents do all of the SDLC work; a human approves
  the spec, the design, policy-flagged changes and every release, and can reject, send back or stop at any point.
- **Never trust, always verify.** No agent output is accepted on its word: real builds, tests, coverage, criterion
  ↔ test mapping, file-by-file review coverage and policy checks decide. When agents were wrong, the evidence was
  in the audit trail; when the checks were wrong, the fix went into the checks (the 19 incidents).
- **AI optimises for the check, not the intent.** The single most important lesson: an agent mocked a database,
  dropped a failing test, or narrowed a requirement when that satisfied the check it was given. Each check was
  rewritten to measure the intent directly.
- **Human decisions are part of the system.** Approval comments and send-back feedback become binding context for
  every later agent — and an imprecise human instruction can mislead an agent just as a bad prompt can.
- **AI-assisted engineering of the orchestrator itself.** The orchestrator was built with an AI coding assistant
  (Claude Code), under the same discipline: every change tested, reviewed by running it against real scenarios,
  committed with explanations, and fixed in the open when the runs exposed a flaw.
- **Cost awareness.** Every run is bounded by time, attempt and token budgets; budget stops are safe and resumable.

## 9. Limitations

- Generated code is built on the host (scrubbed environment, workspace-only directory, timeouts, and the
  dangerous-code policy first). Production use needs a container sandbox.
- Humans are identified by an `actor` field; there is no authentication or role-based approval.
- Single orchestrator instance; the run lock and stop signals are in memory.
- Publishing refuses if `main` moved; there is no automatic rebase.
- Recordings replay by stage and attempt, so a replay must repeat the same human actions (approvals, rejections,
  send-backs) and needs the same orchestrator version; the final runs include send-backs.
- Open follow-ups in the generated service (recorded in the review reports): rate-limiter eviction, a maximum
  request-body size, removing an unreachable request-host fallback, alerting on failed failure-audit writes,
  alphabet validation before logging analytics codes; branch coverage below the 100% target in all three
  scenarios (itemised in each `coverage-gaps.md`).
- Token cost was high while the orchestrator was being hardened (brownfield 1.06M); after the fixes the
  clean greenfield run used 129k.

---

## 10. Setup and demo

Requirements: Java 21. For live runs, `OPENAI_API_KEY` in the environment (never in a file).

```bash
./gradlew :orchestrator:test                       # 71 fast tests
./gradlew :orchestrator:slowTest                   # real Gradle builds of the service template
./gradlew :orchestrator:bootRun                    # dry run with stub agents, no key
SDLC_LLM_MODE=replay ./gradlew :orchestrator:bootRun --args='--spring.profiles.active=llm'   # replay, no key
SDLC_LLM_MODE=record ./gradlew :orchestrator:bootRun --args='--spring.profiles.active=llm'   # live, records answers
```

Drive a run with the demo CLI (`SDLC_URL` points at the orchestrator):

```bash
scripts/sdlc start greenfield            # prints the run id
scripts/sdlc status <runId>              # stages, and the approval waiting for you
scripts/sdlc approve <approvalId>        # or: reject <approvalId> "<feedback>" / rerun <runId> <stage> "<feedback>"
scripts/sdlc audit <runId> 30            # audit trail
scripts/sdlc metrics <runId>             # reliability metrics
scripts/sdlc export <runId> greenfield   # write all SDLC deliverables to deliverables/greenfield/
```

The generated service ends up in `workspace/target/url-shortener` (`main`); each run's history is in
`workspace/runs/<runId>`.

---

## 11. Numbers across all runs

| Metric | Value |
|---|---|
| Runs | 9: 7 succeeded, 1 stopped by the human after incident 4, 1 abandoned after incident 13 |
| Stage executions passing on the first attempt | 84.1% |
| Attempts / rollbacks | 217 / 227 — every failed attempt was undone; nothing unapproved reached `main` |
| Human checkpoints | 58 across runs |
| Orchestrator code | ~158 source files, 23 test classes (71 fast tests + 1 slow), 14 prompt files, 17 gates, 8 policies, 10 agents |
