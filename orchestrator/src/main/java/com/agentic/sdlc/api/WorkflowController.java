package com.agentic.sdlc.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentic.sdlc.metrics.MetricsService;
import com.agentic.sdlc.metrics.ReliabilityMetrics;
import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workflow.WorkflowCatalog;
import com.agentic.sdlc.workflow.WorkflowDefinition;

@RestController
@RequestMapping("/api")
public class WorkflowController {

    public record WorkflowView(String name, String description, List<NodeDefinition> stages) {
        static WorkflowView of(WorkflowDefinition w) {
            return new WorkflowView(w.name(), w.description(), w.topologicalOrder());
        }
    }

    private final WorkflowCatalog catalog;
    private final MetricsService metrics;

    public WorkflowController(WorkflowCatalog catalog, MetricsService metrics) {
        this.catalog = catalog;
        this.metrics = metrics;
    }

    @GetMapping("/workflows")
    public List<WorkflowView> workflows() {
        return catalog.all().stream().map(WorkflowView::of).toList();
    }

    @GetMapping("/workflows/{name}")
    public WorkflowView workflow(@PathVariable String name) {
        return WorkflowView.of(catalog.get(name));
    }

    @GetMapping("/metrics")
    public ReliabilityMetrics metrics() {
        return metrics.overall();
    }
}
