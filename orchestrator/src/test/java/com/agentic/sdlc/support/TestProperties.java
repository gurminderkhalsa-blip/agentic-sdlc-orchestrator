package com.agentic.sdlc.support;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.agentic.sdlc.config.SdlcProperties;

/** Hand-built settings for unit tests that do not start Spring. */
public final class TestProperties {

    private TestProperties() {
    }

    public static SdlcProperties withWorkspace(Path root, Path template) {
        return new SdlcProperties(List.of(), new SdlcProperties.Agents("test"), Duration.ZERO,
                new SdlcProperties.Budget(Duration.ofMinutes(5), 40, 100_000),
                new SdlcProperties.Policy(200_000, 40, 4000), "sdlc",
                new SdlcProperties.Llm("replay", "test-model", null, null, root.resolve("recordings").toString(),
                        1000, Duration.ofSeconds(5), null),
                new SdlcProperties.Workspace(root.toString(), root.resolve("target/repo").toString(),
                        template.toString(), Duration.ofMinutes(5)),
                new SdlcProperties.Gates(0.7), root.resolve("deliverables").toString());
    }

    public static SdlcProperties defaults() {
        return withWorkspace(Path.of("build/unused"), Path.of("build/unused"));
    }
}
