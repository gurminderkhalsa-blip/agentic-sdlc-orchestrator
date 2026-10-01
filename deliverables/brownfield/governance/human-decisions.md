# Human checkpoints and decisions

Approvals are requested at the spec, design and release checkpoints, by policies (configuration, schema changes) and by gates that need a human (clarification, blocking security findings).

| Approval | Stage | Reason | Decision | By | Comment |
|---|---|---|---|---|---|
| #45 | requirements | STAGE_CHECKPOINT | APPROVED | human:claude-for-gurminder | Delegated approval (STAGE_CHECKPOINT at requirements): routine checkpoint, gates passed. Review [requirements_spec] before downstream stages use them |
| #46 | design | STAGE_CHECKPOINT | APPROVED | human:claude-for-gurminder | Delegated approval (STAGE_CHECKPOINT at design): routine checkpoint, gates passed. Review [design_doc, api_contract] before downstream stages use them |
| #47 | implement | POLICY | APPROVED | human:claude-for-gurminder | Delegated approval (POLICY at implement): routine checkpoint, gates passed. protectedFile: src/main/resources/application.yml changed: runtime configuration change needs approval; schemaChange: modifies persisted data model in src/main/java/com/example/shortener/domain/Link.j |
| #48 | security_review | CLARIFICATION | CANCELLED | system | Invalidated by re-planning |
| #49 | implement | POLICY | APPROVED | human:claude-for-gurminder | Delegated approval (POLICY at implement): routine checkpoint, gates passed. protectedFile: src/main/resources/application.yml changed: runtime configuration change needs approval; schemaChange: modifies persisted data model in src/main/java/com/example/shortener/domain/Link.j |
| #50 | review | CLARIFICATION | APPROVED | human:gurminder | Proceed: all 19 acceptance criteria met and the security review passed. Follow-up (accepted): validate the analytics code against the allowed alphabet before logging/auditing instead of only truncating to 7 characters; truncation already bounds the logged value. Addressed with the safety hardening. |
| #51 | release_readiness | STAGE_CHECKPOINT | APPROVED | human:claude-for-gurminder | Delegated approval (STAGE_CHECKPOINT at release_readiness): routine checkpoint, gates passed. Review [release_notes, engineering_summary] before downstream stages use them |

