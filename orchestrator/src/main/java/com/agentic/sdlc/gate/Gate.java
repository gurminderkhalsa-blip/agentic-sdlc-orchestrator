package com.agentic.sdlc.gate;

import com.agentic.sdlc.common.Named;

/** A check at a stage boundary: entry gates guard the start, exit gates guard what gets committed. */
public interface Gate extends Named {

    GateResult check(GateContext context);
}
