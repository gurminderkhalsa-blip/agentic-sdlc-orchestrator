package com.agentic.sdlc.support;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers one {@link ScriptedAgent} bean per name used by the test workflows. */
@TestConfiguration
public class TestAgentsConfig {

    public static final List<String> NAMES = List.of(
            "Alpha", "Beta", "Gamma", "Delta", "Worker", "Backup", "Spec", "Impact", "Planner", "Builder");

    public record TestAgents(Map<String, ScriptedAgent> byName) {
        public ScriptedAgent get(String name) {
            return byName.get(name);
        }

        public void resetAll() {
            byName.values().forEach(ScriptedAgent::reset);
        }
    }

    @Bean
    static BeanDefinitionRegistryPostProcessor scriptedAgentRegistrar() {
        return (BeanDefinitionRegistry registry) -> NAMES.forEach(name -> registry.registerBeanDefinition(
                "scripted" + name, new RootBeanDefinition(ScriptedAgent.class, () -> new ScriptedAgent(name))));
    }

    @Bean
    TestAgents testAgents(List<ScriptedAgent> agents) {
        return new TestAgents(agents.stream().collect(Collectors.toMap(ScriptedAgent::name, Function.identity())));
    }
}
