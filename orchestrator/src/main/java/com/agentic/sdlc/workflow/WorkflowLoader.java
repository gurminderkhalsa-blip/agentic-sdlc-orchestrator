package com.agentic.sdlc.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.agent.AgentRegistry;
import com.agentic.sdlc.condition.ConditionRegistry;
import com.agentic.sdlc.gate.GateRegistry;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses a workflow YAML file and rejects anything that could make execution unsafe or ambiguous:
 * cycles, dangling dependencies, unknown agents/gates/conditions, duplicate outputs and unbounded retries.
 */
@Component
public class WorkflowLoader {

    static final int MAX_RETRIES_LIMIT = 10;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_]*");

    private final YAMLMapper yaml = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
    private final AgentRegistry agents;
    private final GateRegistry gates;
    private final ConditionRegistry conditions;

    public WorkflowLoader(AgentRegistry agents, GateRegistry gates, ConditionRegistry conditions) {
        this.agents = agents;
        this.gates = gates;
        this.conditions = conditions;
    }

    public WorkflowDefinition load(String source, InputStream in) {
        WorkflowFile file;
        try (in) {
            file = yaml.readValue(in, WorkflowFile.class);
        } catch (JacksonException | IOException e) {
            throw new WorkflowValidationException(source, List.of("Cannot parse YAML: " + e.getMessage()));
        }
        return validate(source, file);
    }

    WorkflowDefinition validate(String source, WorkflowFile file) {
        List<String> problems = new ArrayList<>();
        if (file == null || file.name() == null || file.name().isBlank()) {
            problems.add("workflow 'name' is required");
        }
        List<NodeDefinition> nodes = file == null || file.nodes() == null ? List.of() : file.nodes();
        if (nodes.isEmpty()) {
            problems.add("workflow must declare at least one node");
        }

        Map<String, NodeDefinition> byId = new LinkedHashMap<>();
        Map<String, String> producers = new HashMap<>();
        for (NodeDefinition node : nodes) {
            checkNode(node, byId, producers, problems);
            if (!node.writes().isEmpty() && (file == null || !Boolean.TRUE.equals(file.workspace()))) {
                problems.add(node.id() + ": declares 'writes' but the workflow has no workspace");
            }
        }
        for (NodeDefinition node : byId.values()) {
            for (String dep : node.dependsOn()) {
                if (dep.equals(node.id())) {
                    problems.add(node.id() + ": depends on itself");
                } else if (!byId.containsKey(dep)) {
                    problems.add(node.id() + ": depends on unknown node '" + dep + "'");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(source, problems);
        }

        List<NodeDefinition> order = topologicalSort(byId, problems);
        if (!problems.isEmpty()) {
            throw new WorkflowValidationException(source, problems);
        }
        return new WorkflowDefinition(file.name(), file.description(), Boolean.TRUE.equals(file.workspace()),
                List.copyOf(byId.values()), order);
    }

    private void checkNode(NodeDefinition node, Map<String, NodeDefinition> byId, Map<String, String> producers,
            List<String> problems) {
        String id = node.id();
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            problems.add("node id '" + id + "' must match " + ID_PATTERN.pattern());
            return;
        }
        if (byId.putIfAbsent(id, node) != null) {
            problems.add(id + ": duplicate node id");
        }
        if (node.agent() == null || !agents.contains(node.agent())) {
            problems.add(id + ": unknown agent '" + node.agent() + "' (known: " + agents.names() + ")");
        }
        if (node.fallbackAgent() != null && !agents.contains(node.fallbackAgent())) {
            problems.add(id + ": unknown fallback agent '" + node.fallbackAgent() + "'");
        }
        if (node.condition() != null && !conditions.contains(node.condition())) {
            problems.add(id + ": unknown condition '" + node.condition() + "' (known: " + conditions.names() + ")");
        }
        List<String> allGates = new ArrayList<>(node.entryGates());
        allGates.addAll(node.exitGates());
        for (String gate : allGates) {
            if (!gates.contains(gate)) {
                problems.add(id + ": unknown gate '" + gate + "' (known: " + gates.names() + ")");
            }
        }
        if (node.maxRetries() < 0 || node.maxRetries() > MAX_RETRIES_LIMIT) {
            problems.add(id + ": maxRetries must be between 0 and " + MAX_RETRIES_LIMIT);
        }
        if (node.fallbackAttempts() < 1 || node.fallbackAttempts() > MAX_RETRIES_LIMIT) {
            problems.add(id + ": fallbackAttempts must be between 1 and " + MAX_RETRIES_LIMIT);
        }
        for (String output : node.outputs()) {
            String previous = producers.putIfAbsent(output, id);
            if (previous != null) {
                problems.add(id + ": output '" + output + "' is already produced by " + previous);
            }
        }
    }

    /** Kahn's algorithm; any node left over afterwards is part of a cycle. */
    private static List<NodeDefinition> topologicalSort(Map<String, NodeDefinition> byId, List<String> problems) {
        Map<String, Integer> inDegree = new HashMap<>();
        byId.values().forEach(n -> inDegree.put(n.id(), n.dependsOn().size()));
        Deque<String> ready = new ArrayDeque<>();
        byId.values().stream().filter(n -> n.dependsOn().isEmpty()).forEach(n -> ready.add(n.id()));

        List<NodeDefinition> order = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(byId.get(id));
            done.add(id);
            for (NodeDefinition candidate : byId.values()) {
                if (candidate.dependsOn().contains(id) && inDegree.merge(candidate.id(), -1, Integer::sum) == 0) {
                    ready.add(candidate.id());
                }
            }
        }
        if (order.size() != byId.size()) {
            List<String> cyclic = byId.keySet().stream().filter(id -> !done.contains(id)).toList();
            problems.add("dependency cycle involving " + cyclic);
        }
        return order;
    }
}
