package com.agentic.sdlc.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agentic.sdlc.policy.rules.ChangeSizeRule;
import com.agentic.sdlc.policy.rules.DangerousCodeRule;
import com.agentic.sdlc.policy.rules.ProtectedFileRule;
import com.agentic.sdlc.policy.rules.SchemaChangeRule;
import com.agentic.sdlc.policy.rules.SecretScanRule;
import com.agentic.sdlc.policy.rules.TestRemovalRule;
import com.agentic.sdlc.policy.rules.WriteScopeRule;
import com.agentic.sdlc.support.TestProperties;
import com.agentic.sdlc.workspace.FileChange;

class PolicyRulesTest {

    private final PolicyEngine engine = new PolicyEngine(List.of(new WriteScopeRule(), new ProtectedFileRule(),
            new SchemaChangeRule(), new DangerousCodeRule(), new SecretScanRule(), new TestRemovalRule(),
            new ChangeSizeRule(TestProperties.defaults())));

    private PolicyDecision evaluate(List<String> scopes, FileChange... files) {
        return engine.evaluate(new ProposedChange("run", "implement", Map.of(), List.of(files), scopes));
    }

    private static FileChange created(String path, String content) {
        return new FileChange(path, content, null);
    }

    @Test
    void allowsNormalChangesInsideScope() {
        PolicyDecision decision = evaluate(List.of("src/main/**"),
                created("src/main/java/com/example/Svc.java", "class Svc {}"));
        assertThat(decision.verdict()).isEqualTo(PolicyVerdict.ALLOW);
    }

    @Test
    void deniesWritesOutsideTheStagesScope() {
        PolicyDecision decision = evaluate(List.of("src/test/**"),
                created("src/main/java/com/example/Svc.java", "class Svc {}"));
        assertThat(decision.verdict()).isEqualTo(PolicyVerdict.DENY);
        assertThat(decision.describe(PolicyVerdict.DENY)).contains("writeScope");
    }

    @Test
    void buildFileChangesNeedApproval() {
        PolicyDecision decision = evaluate(List.of("src/main/**", "build.gradle"),
                new FileChange("build.gradle", "plugins {}", "old"));
        assertThat(decision.verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
    }

    @Test
    void modifyingAnExistingEntityNeedsApprovalButCreatingOneDoesNot() {
        String entity = "@Entity class Link {}";
        assertThat(evaluate(List.of("src/main/**"), created("src/main/java/Link.java", entity)).verdict())
                .isEqualTo(PolicyVerdict.ALLOW);
        assertThat(evaluate(List.of("src/main/**"),
                new FileChange("src/main/java/Link.java", entity + " // v2", entity)).verdict())
                .isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
    }

    @Test
    void deniesDangerousCodeAndSecrets() {
        assertThat(evaluate(List.of("src/main/**"), created("src/main/java/X.java",
                "class X { void a() throws Exception { Runtime.getRuntime().exec(\"rm\"); } }")).verdict())
                .isEqualTo(PolicyVerdict.DENY);
        assertThat(evaluate(List.of("src/main/**"), created("src/main/resources/x.txt",
                "password = \"hunter22\"")).verdict()).isEqualTo(PolicyVerdict.DENY);
    }

    @Test
    void updatingATestIsAllowedButRemovingTestsNeedsApproval() {
        String two = "class T { @Test void a() {} @Test void b() {} }";
        String twoUpdated = "class T { @Test void a() { /* new behaviour */ } @Test void b() {} }";
        String one = "class T { @Test void a() {} }";
        List<String> scope = List.of("src/test/**");

        assertThat(evaluate(scope, new FileChange("src/test/java/T.java", twoUpdated, two)).verdict())
                .isEqualTo(PolicyVerdict.ALLOW);
        assertThat(evaluate(scope, new FileChange("src/test/java/T.java", one, two)).verdict())
                .isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
        assertThat(evaluate(scope, new FileChange("src/test/java/T.java", null, two)).describe(PolicyVerdict.REQUIRE_APPROVAL))
                .contains("file deleted");
    }

    @Test
    void deniesOversizedChanges() {
        String big = "x\n".repeat(4001);
        assertThat(evaluate(List.of("src/main/**"), created("src/main/java/Big.java", big)).describe(PolicyVerdict.DENY))
                .contains("changeSize");
    }
}
