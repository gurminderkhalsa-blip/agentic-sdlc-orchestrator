package com.agentic.sdlc.condition;

import java.util.function.Predicate;

import com.agentic.sdlc.common.Named;

/** Decides at scheduling time whether an optional stage runs or is SKIPPED. Re-evaluated after re-planning. */
public interface NodeCondition extends Named, Predicate<ConditionContext> {
}
