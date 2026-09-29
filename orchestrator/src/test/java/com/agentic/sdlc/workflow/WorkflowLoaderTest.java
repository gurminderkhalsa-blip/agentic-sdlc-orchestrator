package com.agentic.sdlc.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.agentic.sdlc.agent.AgentRegistry;
import com.agentic.sdlc.condition.ConditionRegistry;
import com.agentic.sdlc.condition.NodeCondition;
import com.agentic.sdlc.condition.ConditionContext;
import com.agentic.sdlc.gate.Gate;
import com.agentic.sdlc.gate.GateContext;
import com.agentic.sdlc.gate.GateRegistry;
import com.agentic.sdlc.gate.GateResult;
import com.agentic.sdlc.support.ScriptedAgent;

class WorkflowLoaderTest {

    private final WorkflowLoader loader = new WorkflowLoader(
            new AgentRegistry(List.of(new ScriptedAgent("A"), new ScriptedAgent("B"))),
            new GateRegistry(List.of(gate("ok"))),
            new ConditionRegistry(List.of(condition("always"))));

    @Test
    void loadsValidWorkflowInDependencyOrder() {
        WorkflowDefinition workflow = load("""
                name: demo
                nodes:
                  - id: last
                    agent: B
                    dependsOn: [first, middle]
                  - id: middle
                    agent: A
                    dependsOn: [first]
                    condition: always
                    outputs: [m]
                  - id: first
                    agent: A
                    outputs: [f]
                    exitGates: [ok]
                """);

        assertThat(workflow.topologicalOrder()).extracting(NodeDefinition::id)
                .containsExactly("first", "middle", "last");
        assertThat(workflow.descendantsOf("first")).containsExactlyInAnyOrder("middle", "last");
        assertThat(workflow.inputArtifactsOf("last")).containsExactly("f", "m");
        assertThat(workflow.producerOf("m")).contains("middle");
    }

    @Test
    void rejectsCycles() {
        assertProblems("""
                name: cyclic
                nodes:
                  - {id: x, agent: A, dependsOn: [z]}
                  - {id: y, agent: A, dependsOn: [x]}
                  - {id: z, agent: A, dependsOn: [y]}
                """, "dependency cycle");
    }

    @Test
    void rejectsUnknownReferences() {
        assertProblems("""
                name: broken
                nodes:
                  - {id: x, agent: Nobody, dependsOn: [ghost], exitGates: [missing], condition: never}
                """, "unknown agent 'Nobody'", "unknown node 'ghost'", "unknown gate 'missing'",
                "unknown condition 'never'");
    }

    @Test
    void rejectsUnboundedRetriesAndDuplicateOutputs() {
        assertProblems("""
                name: unsafe
                nodes:
                  - {id: x, agent: A, maxRetries: 50, outputs: [o]}
                  - {id: y, agent: A, outputs: [o]}
                """, "maxRetries must be between 0 and 10", "output 'o' is already produced by x");
    }

    @Test
    void rejectsUnknownYamlKeys() {
        assertProblems("""
                name: typo
                nodes:
                  - {id: x, agent: A, requiresAproval: true}
                """, "Cannot parse YAML");
    }

    private WorkflowDefinition load(String yaml) {
        return loader.load("test", new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertProblems(String yaml, String... expected) {
        assertThatThrownBy(() -> load(yaml))
                .isInstanceOf(WorkflowValidationException.class)
                .satisfies(e -> {
                    String all = String.join("\n", ((WorkflowValidationException) e).problems());
                    for (String fragment : expected) {
                        assertThat(all).contains(fragment);
                    }
                });
    }

    private static Gate gate(String name) {
        return new Gate() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GateResult check(GateContext context) {
                return GateResult.pass();
            }
        };
    }

    private static NodeCondition condition(String name) {
        return new NodeCondition() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean test(ConditionContext context) {
                return true;
            }
        };
    }
}
