# Demo scenarios

Run them in order: the brownfield scenario changes the service the greenfield scenario released.
Start the orchestrator with the `llm` profile first (see the main README).

| Scenario | Requirement file | What it demonstrates |
|---|---|---|
| Greenfield | [greenfield.md](greenfield.md) | Full lifecycle from an empty template: spec, plan, design, code, tests, docs and security review in parallel, review, release |
| Brownfield | [brownfield.md](brownfield.md) | Impact analysis on real code, `impactFilesExist` gate, schema-change policy approval, regression tests |
| Ambiguous | [ambiguous.md](ambiguous.md) | Clarification checkpoint (`NEEDS_HUMAN`), human answers fed back, re-planning after a spec revision |

Recordings of the model's answers are saved in `recordings/<name>.json` when the orchestrator runs with
`SDLC_LLM_MODE=record`, and replayed with `SDLC_LLM_MODE=replay` (no API key needed; builds and tests
still run for real).
