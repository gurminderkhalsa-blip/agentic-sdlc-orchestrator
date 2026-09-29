package com.agentic.sdlc.condition;

import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.common.Registry;

@Component
public class ConditionRegistry extends Registry<NodeCondition> {
    public ConditionRegistry(List<NodeCondition> conditions) {
        super("condition", conditions);
    }
}
