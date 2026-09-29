package com.agentic.sdlc.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.agentic.sdlc.common.ConflictException;
import com.agentic.sdlc.support.TestProperties;

class WorkspaceServiceTest {

    @TempDir
    Path tmp;

    private WorkspaceService service;

    @BeforeEach
    void setUp() throws Exception {
        Path template = Files.createDirectories(tmp.resolve("template/src/main/java/demo"));
        Files.writeString(template.resolve("App.java"), "class App {}");
        Files.writeString(tmp.resolve("template/README.md"), "# demo");
        service = new WorkspaceService(TestProperties.withWorkspace(tmp.resolve("ws"), tmp.resolve("template")),
                new BuildRunner());
    }

    @AfterEach
    void tearDown() {
        service.closeAll();
    }

    @Test
    void preparesTargetFromTemplateAndClonesOntoRunBranch() {
        Workspace ws = service.prepare("run-aaaaaaaa-1");

        assertThat(ws.branch()).isEqualTo("run/run-aaaa");
        assertThat(ws.listFiles()).containsExactly("README.md", "src/main/java/demo/App.java");
        assertThat(ws.headCommit()).isEqualTo(service.targetHead());
    }

    @Test
    void rollbackRestoresModifiedFilesAndDeletesNewOnes() {
        Workspace ws = service.prepare("run-rollback");
        WorkspaceSession session = ws.session("implement", 1, "agent:X");
        session.write("README.md", "changed");
        session.write("src/main/java/demo/New.java", "class New {}");

        assertThat(session.changes()).extracting(FileChange::path)
                .containsExactly("README.md", "src/main/java/demo/New.java");
        session.rollback();

        assertThat(ws.read("README.md")).contains("# demo");
        assertThat(ws.read("src/main/java/demo/New.java")).isEmpty();
    }

    @Test
    void checkpointCommitsOnlyTheSessionsOwnFiles() {
        Workspace ws = service.prepare("run-parallel");
        WorkspaceSession docs = ws.session("docs", 1, "agent:Docs");
        WorkspaceSession tests = ws.session("tests", 1, "agent:Tests");
        docs.write("docs/api.md", "api");
        tests.write("src/test/java/demo/AppTest.java", "class AppTest {}");

        String sha = docs.checkpoint("docs");
        tests.rollback();

        assertThat(sha).isEqualTo(ws.headCommit());
        assertThat(ws.readAtHead("docs/api.md")).contains("api");
        assertThat(ws.readAtHead("src/test/java/demo/AppTest.java")).isEmpty();
        assertThat(ws.read("docs/api.md")).contains("api");
    }

    @Test
    void revertStageUndoesItsCheckpointsButKeepsOthers() {
        Workspace ws = service.prepare("run-revert");
        WorkspaceSession impl = ws.session("implement", 1, "agent:Impl");
        impl.write("src/main/java/demo/Svc.java", "class Svc {}");
        impl.checkpoint("impl");
        WorkspaceSession docs = ws.session("docs", 1, "agent:Docs");
        docs.write("docs/api.md", "api");
        docs.checkpoint("docs");

        String baseline = service.targetHead();
        assertThat(ws.revertStage("implement", baseline)).hasSize(1);
        assertThat(ws.revertStage("implement", baseline)).isEmpty();
        assertThat(ws.read("src/main/java/demo/Svc.java")).isEmpty();
        assertThat(ws.read("docs/api.md")).contains("api");
    }

    @Test
    void revertNeverTouchesCommitsInheritedFromEarlierRuns() {
        // Run 1 (greenfield) implements and releases.
        Workspace greenfield = service.prepare("run-green");
        WorkspaceSession first = greenfield.session("implement", 1, "agent:Impl");
        first.write("src/main/java/demo/Released.java", "class Released {}");
        first.checkpoint("released code");
        service.publish("run-green");

        // Run 2 (brownfield) starts from that release, implements, then is sent back.
        Workspace brownfield = service.prepare("run-brown");
        String baseline = brownfield.headCommit();
        WorkspaceSession second = brownfield.session("implement", 1, "agent:Impl");
        second.write("src/main/java/demo/Feature.java", "class Feature {}");
        second.checkpoint("feature");

        assertThat(brownfield.revertStage("implement", baseline)).hasSize(1);
        assertThat(brownfield.read("src/main/java/demo/Feature.java")).isEmpty();
        assertThat(brownfield.read("src/main/java/demo/Released.java")).hasValue("class Released {}");
    }

    @Test
    void publishFastForwardsMainAndRefusesWhenMainMoved() {
        Workspace first = service.prepare("run-first");
        Workspace second = service.prepare("run-second");
        WorkspaceSession a = first.session("implement", 1, "agent:A");
        a.write("src/main/java/demo/A.java", "class A {}");
        a.checkpoint("a");
        WorkspaceSession b = second.session("implement", 1, "agent:B");
        b.write("src/main/java/demo/B.java", "class B {}");
        b.checkpoint("b");

        String published = service.publish("run-first");

        assertThat(published).isEqualTo(first.headCommit());
        assertThat(service.targetFiles()).contains("src/main/java/demo/A.java");
        assertThatThrownBy(() -> service.publish("run-second")).isInstanceOf(ConflictException.class)
                .hasMessageContaining("main moved");
    }

    @Test
    void sandboxRejectsEscapesAndProtectedPaths() {
        WorkspaceSession session = service.prepare("run-sandbox").session("implement", 1, "agent:X");

        for (String path : new String[] { "../outside.txt", "/etc/passwd", ".git/config", "gradlew",
                "gradle/wrapper/gradle-wrapper.properties", "src/../../escape" }) {
            assertThatThrownBy(() -> session.write(path, "x")).as(path)
                    .isInstanceOf(WorkspaceAccessException.class);
        }
    }

    @Test
    void workspaceIsReopenedFromDiskAfterRestart() {
        service.prepare("run-restart");
        service.closeAll();

        assertThat(service.find("run-restart")).isPresent();
        assertThat(service.find("run-unknown")).isEmpty();
    }

    @Test
    void buildEnvironmentIsScrubbedOfSecrets() {
        var env = new java.util.HashMap<String, String>(java.util.Map.of(
                "OPENAI_API_KEY", "sk", "GITHUB_TOKEN", "t", "DB_PASSWORD", "p", "PATH", "/bin", "HOME", "/h"));
        BuildRunner.scrub(env);
        assertThat(env).containsOnlyKeys("PATH", "HOME");
    }
}
