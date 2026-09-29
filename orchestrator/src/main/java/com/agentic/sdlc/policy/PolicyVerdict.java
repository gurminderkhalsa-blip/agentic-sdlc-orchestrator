package com.agentic.sdlc.policy;

/** Ordered from least to most severe, so the aggregate verdict is simply the maximum. */
public enum PolicyVerdict {
    ALLOW, REQUIRE_APPROVAL, DENY
}
