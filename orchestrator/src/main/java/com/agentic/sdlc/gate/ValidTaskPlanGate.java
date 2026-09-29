package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exit gate: the task plan is a real decomposition: unique ids, known dependencies, no cycles. */
@Component
public class ValidTaskPlanGate implements Gate {

    private final ObjectMapper json;

    public ValidTaskPlanGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "validTaskPlan";
    }

    @Override
    public GateResult check(GateContext context) {
        JsonNode plan = JsonArtifacts.read(json, context, "task_plan").orElse(null);
        if (plan == null) {
            return GateResult.fail("task_plan is missing or not JSON");
        }
        Map<String, List<String>> deps = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (JsonNode task : plan.path("tasks")) {
            String id = task.path("id").asString("");
            if (id.isBlank() || deps.containsKey(id)) {
                problems.add("task id '" + id + "' is blank or duplicated");
                continue;
            }
            List<String> dependsOn = new ArrayList<>();
            task.path("dependsOn").forEach(d -> dependsOn.add(d.asString()));
            deps.put(id, dependsOn);
        }
        if (deps.size() < 2) {
            problems.add("plan has " + deps.size() + " task(s); decompose the work into at least 2");
        }
        deps.forEach((id, list) -> list.stream().filter(d -> !deps.containsKey(d))
                .forEach(d -> problems.add(id + " depends on unknown task " + d)));
        if (problems.isEmpty() && hasCycle(deps)) {
            problems.add("task dependencies contain a cycle");
        }
        return problems.isEmpty()
                ? GateResult.pass().withEvidence(Map.of("tasks", deps.size()))
                : GateResult.fail(String.join("; ", problems));
    }

    private static boolean hasCycle(Map<String, List<String>> deps) {
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String id : deps.keySet()) {
            if (visit(id, deps, visiting, done)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visit(String id, Map<String, List<String>> deps, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) {
            return false;
        }
        if (!visiting.add(id)) {
            return true;
        }
        for (String dep : deps.getOrDefault(id, List.of())) {
            if (visit(dep, deps, visiting, done)) {
                return true;
            }
        }
        visiting.remove(id);
        done.add(id);
        return false;
    }
}
