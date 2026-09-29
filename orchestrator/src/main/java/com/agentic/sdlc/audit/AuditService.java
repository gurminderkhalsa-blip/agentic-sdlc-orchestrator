package com.agentic.sdlc.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.agentic.sdlc.state.AuditEvent;
import com.agentic.sdlc.state.AuditEventRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Writes every significant action to the append-only audit table and, in parallel, as one JSON line to the
 * AUDIT logger (runId in the MDC), so the trail can be shipped to any log platform.
 */
@Service
public class AuditService {

    private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

    public static final String SYSTEM = "system";

    private final AuditEventRepository events;
    private final ObjectMapper json;

    public AuditService(AuditEventRepository events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    public static String agentActor(String agent) {
        return "agent:" + agent;
    }

    public static String humanActor(String user) {
        return "human:" + user;
    }

    public void record(String runId, String nodeId, String type, String actor, String message) {
        record(runId, nodeId, type, actor, message, null);
    }

    public void record(String runId, String nodeId, String type, String actor, String message, Map<String, ?> details) {
        AuditEvent event = new AuditEvent();
        event.setRunId(runId);
        event.setNodeId(nodeId);
        event.setType(type);
        event.setActor(actor);
        event.setMessage(truncate(message));
        event.setDetails(details == null || details.isEmpty() ? null : json.writeValueAsString(details));
        event.setTimestamp(Instant.now());
        events.save(event);

        try (MDC.MDCCloseable ignored = MDC.putCloseable("runId", runId)) {
            AUDIT_LOG.info(json.writeValueAsString(Map.of(
                    "ts", event.getTimestamp().toString(), "runId", runId, "node", nodeId == null ? "-" : nodeId,
                    "type", type, "actor", actor, "message", event.getMessage() == null ? "" : event.getMessage())));
        }
    }

    public List<AuditEvent> trail(String runId) {
        return events.findByRunIdOrderByIdAsc(runId);
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 4000 ? message : message.substring(0, 3990) + "...";
    }
}
