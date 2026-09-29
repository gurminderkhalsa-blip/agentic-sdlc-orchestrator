package com.agentic.sdlc.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.agentic.sdlc.common.NotFoundException;

/**
 * A validated, immutable workflow graph. Only {@link WorkflowLoader} creates these, after checking that the
 * graph is acyclic and every reference resolves, so the helpers below can assume a well-formed DAG.
 */
public final class WorkflowDefinition {

    private final String name;
    private final String description;
    private final boolean workspace;
    private final Map<String, NodeDefinition> nodes;
    private final List<NodeDefinition> topologicalOrder;
    private final Map<String, List<String>> children;
    private final Map<String, String> producerByArtifact;

    WorkflowDefinition(String name, String description, boolean workspace, List<NodeDefinition> nodes,
            List<NodeDefinition> topologicalOrder) {
        this.name = name;
        this.description = description;
        this.workspace = workspace;
        this.nodes = Collections.unmodifiableMap(toMap(nodes));
        this.topologicalOrder = List.copyOf(topologicalOrder);
        this.children = buildChildren(nodes);
        this.producerByArtifact = buildProducers(nodes);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** True when stages change code in a per-run git workspace (cloned from the target repository). */
    public boolean usesWorkspace() {
        return workspace;
    }

    public NodeDefinition node(String id) {
        NodeDefinition node = nodes.get(id);
        if (node == null) {
            throw new NotFoundException("Workflow " + name + " has no stage " + id);
        }
        return node;
    }

    /** Stages ordered so that every stage comes after all of its dependencies. */
    public List<NodeDefinition> topologicalOrder() {
        return topologicalOrder;
    }

    public Optional<String> producerOf(String artifactName) {
        return Optional.ofNullable(producerByArtifact.get(artifactName));
    }

    /** Every stage that directly or indirectly depends on {@code id}; these are invalidated when its output changes. */
    public Set<String> descendantsOf(String id) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(children.getOrDefault(id, List.of()));
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (result.add(next)) {
                queue.addAll(children.getOrDefault(next, List.of()));
            }
        }
        return result;
    }

    /** Every stage that {@code id} directly or indirectly depends on. */
    public Set<String> ancestorsOf(String id) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(node(id).dependsOn());
        while (!queue.isEmpty()) {
            String next = queue.poll();
            if (result.add(next)) {
                queue.addAll(node(next).dependsOn());
            }
        }
        return result;
    }

    /** Artifacts a stage may consume: the outputs of all its ancestors. Used for lineage and staleness. */
    public List<String> inputArtifactsOf(String id) {
        List<String> inputs = new ArrayList<>();
        for (NodeDefinition candidate : topologicalOrder) {
            if (ancestorsOf(id).contains(candidate.id())) {
                inputs.addAll(candidate.outputs());
            }
        }
        return inputs;
    }

    private static Map<String, NodeDefinition> toMap(List<NodeDefinition> nodes) {
        Map<String, NodeDefinition> map = new LinkedHashMap<>();
        nodes.forEach(n -> map.put(n.id(), n));
        return map;
    }

    private static Map<String, List<String>> buildChildren(List<NodeDefinition> nodes) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (NodeDefinition node : nodes) {
            for (String dep : node.dependsOn()) {
                map.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.id());
            }
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> buildProducers(List<NodeDefinition> nodes) {
        Map<String, String> map = new LinkedHashMap<>();
        nodes.forEach(n -> n.outputs().forEach(out -> map.put(out, n.id())));
        return Collections.unmodifiableMap(map);
    }
}
