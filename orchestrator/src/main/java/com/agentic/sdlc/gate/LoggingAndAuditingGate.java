package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.workspace.Workspace;

/**
 * Exit gate for implementation: every controller and service logs through SLF4J, and the service keeps an
 * application audit trail (a JPA entity whose name contains "Audit"). A static check, run after compiling.
 */
@Component
public class LoggingAndAuditingGate implements Gate {

    private static final Pattern COMPONENT = Pattern.compile("@(RestController|Controller|Service)\\b");
    private static final Pattern LOGGER = Pattern.compile("LoggerFactory\\.getLogger|@Slf4j");
    private static final Pattern AUDIT_ENTITY = Pattern.compile("@Entity[\\s\\S]{0,400}?class\\s+\\w*Audit\\w*");

    @Override
    public String name() {
        return "loggingAndAuditing";
    }

    @Override
    public GateResult check(GateContext context) {
        Workspace workspace = context.workspace().orElse(null);
        if (workspace == null) {
            return GateResult.fail("no workspace");
        }
        List<String> withoutLogging = new ArrayList<>();
        int components = 0;
        boolean auditEntity = false;
        for (String path : workspace.listFiles()) {
            if (!path.startsWith("src/main/java/") || !path.endsWith(".java")) {
                continue;
            }
            String source = workspace.read(path).orElse("");
            if (COMPONENT.matcher(source).find()) {
                components++;
                if (!LOGGER.matcher(source).find()) {
                    withoutLogging.add(path.substring(path.lastIndexOf('/') + 1));
                }
            }
            auditEntity |= AUDIT_ENTITY.matcher(source).find();
        }
        List<String> problems = new ArrayList<>();
        if (!withoutLogging.isEmpty()) {
            problems.add("these controllers/services have no SLF4J logger: " + withoutLogging);
        }
        if (!auditEntity) {
            problems.add("no audit trail: add an @Entity whose class name contains \"Audit\" and record actions in it");
        }
        Map<String, Object> evidence = Map.of("componentsChecked", components, "withoutLogging", withoutLogging,
                "auditEntity", auditEntity);
        return problems.isEmpty() ? GateResult.pass().withEvidence(evidence)
                : GateResult.fail(String.join("; ", problems)).withEvidence(evidence);
    }
}
