package com.agentic.sdlc.workflow;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.agentic.sdlc.common.NotFoundException;
import com.agentic.sdlc.config.SdlcProperties;

/** Loads and validates every workflow file at startup, so a broken workflow stops the app instead of a run. */
@Component
public class WorkflowCatalog {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCatalog.class);

    private final Map<String, WorkflowDefinition> workflows = new TreeMap<>();

    public WorkflowCatalog(SdlcProperties properties, WorkflowLoader loader) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (String location : properties.workflowLocations()) {
            for (Resource resource : resolver.getResources(location)) {
                WorkflowDefinition workflow = loader.load(resource.getDescription(), resource.getInputStream());
                if (workflows.putIfAbsent(workflow.name(), workflow) != null) {
                    throw new IllegalStateException("Duplicate workflow name: " + workflow.name());
                }
                log.info("Loaded workflow '{}' with {} stages from {}", workflow.name(),
                        workflow.topologicalOrder().size(), resource.getFilename());
            }
        }
        if (workflows.isEmpty()) {
            throw new IllegalStateException("No workflows found at " + properties.workflowLocations());
        }
    }

    public WorkflowDefinition get(String name) {
        WorkflowDefinition workflow = workflows.get(name);
        if (workflow == null) {
            throw new NotFoundException("Unknown workflow: " + name + " (known: " + workflows.keySet() + ")");
        }
        return workflow;
    }

    public Collection<WorkflowDefinition> all() {
        return workflows.values();
    }
}
