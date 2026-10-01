# Human checkpoints and decisions

Approvals are requested at the spec, design and release checkpoints, by policies (configuration, schema changes) and by gates that need a human (clarification, blocking security findings).

| Approval | Stage | Reason | Decision | By | Comment |
|---|---|---|---|---|---|
| #39 | requirements | STAGE_CHECKPOINT | APPROVED | human:claude-for-gurminder | Delegated approval (STAGE_CHECKPOINT at requirements): routine checkpoint, gates passed. Review [requirements_spec] before downstream stages use them |
| #40 | design | STAGE_CHECKPOINT | APPROVED | human:claude-for-gurminder | Delegated approval (STAGE_CHECKPOINT at design): routine checkpoint, gates passed. Review [design_doc, api_contract] before downstream stages use them |
| #41 | review | CLARIFICATION | CANCELLED | system | Invalidated by re-planning |
| #42 | security_review | CLARIFICATION | CANCELLED | system | Invalidated by re-planning |
| #43 | review | CLARIFICATION | CANCELLED | system | Invalidated by re-planning |
| #44 | release_readiness | STAGE_CHECKPOINT | APPROVED | human:claude-for-gurminder | Delegated approval (STAGE_CHECKPOINT at release_readiness): routine checkpoint, gates passed. Review [release_notes, engineering_summary] before downstream stages use them |

