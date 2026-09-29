package com.agentic.sdlc.agent.stub;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.dataformat.yaml.YAMLMapper;

/** Registers one {@link StubAgent} bean per entry in {@code stubs/stub-agents.yaml} when sdlc.agents.mode=stub. */
@Configuration
@ConditionalOnProperty(name = "sdlc.agents.mode", havingValue = "stub", matchIfMissing = true)
public class StubAgentConfig {

    record StubSpec(String name, Map<String, String> outputs, Integer failFirstAttempts) {
    }

    record StubFile(List<StubSpec> agents) {
    }

    @Bean
    static BeanDefinitionRegistryPostProcessor stubAgentRegistrar() {
        return (BeanDefinitionRegistry registry) -> {
            for (StubSpec spec : readSpecs()) {
                RootBeanDefinition definition = new RootBeanDefinition(StubAgent.class, () -> new StubAgent(
                        spec.name(), spec.outputs() == null ? Map.of() : spec.outputs(),
                        spec.failFirstAttempts() == null ? 0 : spec.failFirstAttempts()));
                registry.registerBeanDefinition("stubAgent" + spec.name(), definition);
            }
        };
    }

    private static List<StubSpec> readSpecs() {
        try (InputStream in = new ClassPathResource("stubs/stub-agents.yaml").getInputStream()) {
            return new YAMLMapper().readValue(in, StubFile.class).agents();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read stubs/stub-agents.yaml", e);
        }
    }
}
