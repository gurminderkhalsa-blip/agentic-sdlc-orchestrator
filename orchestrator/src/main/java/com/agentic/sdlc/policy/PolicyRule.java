package com.agentic.sdlc.policy;

import java.util.List;

import com.agentic.sdlc.common.Named;

/** A guardrail evaluated on every proposed change. Returns no findings when the change is acceptable. */
public interface PolicyRule extends Named {

    List<PolicyFinding> evaluate(ProposedChange change);
}
