package com.agentic.sdlc.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentic.sdlc.support.TestProperties;
import com.agentic.sdlc.workflow.NodeDefinition;
import com.agentic.sdlc.workspace.BuildRunner;
import com.agentic.sdlc.workspace.Workspace;
import com.agentic.sdlc.workspace.WorkspaceService;
import com.agentic.sdlc.workspace.WorkspaceSession;

/** Runs real Gradle builds of the service template: ./gradlew :orchestrator:slowTest */
@Tag("slow")
class BuildGatesSlowTest {

    @TempDir
    Path tmp;

    private WorkspaceService service;

    @AfterEach
    void close() {
        if (service != null) {
            service.find("slow").ifPresent(ws -> { });
        }
    }

    private GateContext context(Workspace ws) {
        NodeDefinition node = new NodeDefinition("tests", "TestAgent", List.of(), null, List.of(), List.of(),
                List.of(), false, 0, null, 1, List.of("src/**"), false);
        return new GateContext("slow", node, List.of(), name -> Optional.empty(), Optional.of(ws));
    }

    @Test
    void gatesReportCompileErrorsTestFailuresAndCoverage() {
        Path template = Path.of("../templates/spring-boot-service").toAbsolutePath().normalize();
        var properties = TestProperties.withWorkspace(tmp.resolve("ws"), template);
        service = new WorkspaceService(properties, new BuildRunner());
        Workspace ws = service.prepare("slow");
        WorkspaceSession session = ws.session("implement", 1, "agent:test");
        GateContext ctx = context(ws);

        session.write("src/main/java/com/example/shortener/Broken.java", "package com.example.shortener; class Broken { int x = ; }");
        GateResult broken = new CompilesGate().check(ctx);
        assertThat(broken.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(broken.message()).contains("src/main/java/com/example/shortener/Broken.java:1: error");

        session.write("src/main/java/com/example/shortener/Broken.java", """
                package com.example.shortener;
                public class Broken {
                    public static int twice(int v) { return v * 2; }
                    public static int unused(int v) { if (v > 0) { return 1; } return 0; }
                }""");
        session.write("src/test/java/com/example/shortener/BrokenTest.java", """
                package com.example.shortener;
                import static org.assertj.core.api.Assertions.assertThat;
                import org.junit.jupiter.api.Test;
                class BrokenTest {
                    @Test void doubles() { assertThat(Broken.twice(2)).isEqualTo(5); }
                }""");
        assertThat(new CompilesGate().check(ctx).outcome()).isEqualTo(GateResult.Outcome.PASS);
        GateResult failing = new TestsPassGate().check(ctx);
        assertThat(failing.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(failing.message()).contains("BrokenTest.doubles");
        assertThat(failing.evidence()).containsEntry("failures", 1);
        assertThat(new ExistingTestsPassGate().check(ctx).outcome()).isEqualTo(GateResult.Outcome.FAIL);

        session.write("src/test/java/com/example/shortener/BrokenTest.java", """
                package com.example.shortener;
                import static org.assertj.core.api.Assertions.assertThat;
                import org.junit.jupiter.api.Test;
                class BrokenTest {
                    @Test void doubles() { assertThat(Broken.twice(2)).isEqualTo(4); }
                }""");
        GateResult passing = new TestsPassGate().check(ctx);
        assertThat(passing.outcome()).as(passing.message()).isEqualTo(GateResult.Outcome.PASS);
        assertThat(passing.evidence()).containsEntry("tests", 2);
        assertThat(new ExistingTestsPassGate().check(ctx).outcome()).isEqualTo(GateResult.Outcome.PASS);

        GateResult coverage = new CoverageGate(TestProperties.defaults()).check(ctx);
        assertThat(coverage.evidence()).containsKey("lineCoverage");
        assertThat((double) coverage.evidence().get("lineCoverage")).isBetween(0.1, 1.0);

        // A later attempt whose tests no longer compile must report the compile error, not the previous
        // attempt's (still on disk) test results.
        session.write("src/test/java/com/example/shortener/BrokenTest.java", """
                package com.example.shortener;
                import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
                class BrokenTest { }""");
        GateResult stale = new TestsPassGate().check(ctx);
        assertThat(stale.outcome()).isEqualTo(GateResult.Outcome.FAIL);
        assertThat(stale.message()).startsWith("Compilation errors:")
                .contains("src/test/java/com/example/shortener/BrokenTest.java:2: error")
                .doesNotContain(tmp.toString());
    }
}
