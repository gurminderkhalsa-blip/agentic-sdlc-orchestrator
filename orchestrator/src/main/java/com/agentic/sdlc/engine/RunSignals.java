package com.agentic.sdlc.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** In-memory stop flags that running agents poll between steps; the durable status lives on WorkflowRun. */
@Component
public class RunSignals {

    private final Map<String, String> stopRequests = new ConcurrentHashMap<>();

    public void requestStop(String runId, String reason) {
        stopRequests.put(runId, reason);
    }

    public void clearStop(String runId) {
        stopRequests.remove(runId);
    }

    public boolean isStopRequested(String runId) {
        return stopRequests.containsKey(runId);
    }
}
