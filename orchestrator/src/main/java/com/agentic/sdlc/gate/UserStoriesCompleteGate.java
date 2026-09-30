package com.agentic.sdlc.gate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exit gate for the requirements stage: the spec has user stories in "As a / I want / so that" form, each
 * story names the acceptance criteria that prove it, and no acceptance criterion is left without a story.
 */
@Component
public class UserStoriesCompleteGate implements Gate {

    private final ObjectMapper json;

    public UserStoriesCompleteGate(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String name() {
        return "userStoriesComplete";
    }

    @Override
    public GateResult check(GateContext context) {
        JsonNode spec = JsonArtifacts.read(json, context, "requirements_spec").orElse(null);
        if (spec == null) {
            return GateResult.fail("requirements_spec is missing or not JSON");
        }
        Set<String> criteria = new LinkedHashSet<>();
        spec.path("acceptanceCriteria").forEach(ac -> criteria.add(ac.path("id").asString("")));
        Set<String> linked = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        int stories = 0;
        for (JsonNode story : spec.path("userStories")) {
            stories++;
            String id = story.path("id").asString("?");
            for (String field : List.of("asA", "iWant", "soThat")) {
                if (story.path(field).asString("").isBlank()) {
                    problems.add(id + " has no \"" + field + "\"");
                }
            }
            if (story.path("acceptanceCriteria").isEmpty()) {
                problems.add(id + " lists no acceptance criteria");
            }
            story.path("acceptanceCriteria").forEach(ac -> {
                if (!criteria.contains(ac.asString())) {
                    problems.add(id + " refers to unknown criterion " + ac.asString());
                }
                linked.add(ac.asString());
            });
        }
        if (stories == 0) {
            problems.add("the spec has no userStories");
        }
        Set<String> orphans = new LinkedHashSet<>(criteria);
        orphans.removeAll(linked);
        if (!orphans.isEmpty()) {
            problems.add("acceptance criteria " + orphans + " belong to no user story");
        }
        Map<String, Object> evidence = Map.of("userStories", stories, "acceptanceCriteria", criteria.size());
        return problems.isEmpty() ? GateResult.pass().withEvidence(evidence)
                : GateResult.fail(String.join("; ", problems)).withEvidence(evidence);
    }
}
