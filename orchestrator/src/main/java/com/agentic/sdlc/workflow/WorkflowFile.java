package com.agentic.sdlc.workflow;

import java.util.List;

/** Raw shape of a workflow YAML file, before validation. */
record WorkflowFile(String name, String description, Boolean workspace, List<NodeDefinition> nodes) {
}
