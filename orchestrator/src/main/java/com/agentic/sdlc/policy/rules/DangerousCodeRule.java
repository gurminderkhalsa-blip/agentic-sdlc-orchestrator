package com.agentic.sdlc.policy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyRule;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;
import com.agentic.sdlc.workspace.FileChange;

/**
 * Security guardrail evaluated before any generated code is compiled or run: blocks process execution,
 * JVM exits, unsafe deserialisation and dynamic code loading.
 */
@Component
public class DangerousCodeRule implements PolicyRule {

    private static final Map<String, Pattern> PATTERNS = Map.of(
            "process execution", Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|new\\s+ProcessBuilder"),
            "JVM exit", Pattern.compile("System\\.exit\\s*\\("),
            "Java deserialisation", Pattern.compile("new\\s+ObjectInputStream"),
            "dynamic code loading", Pattern.compile("ScriptEngineManager|URLClassLoader|defineClass\\s*\\("),
            "disabled TLS verification", Pattern.compile("TrustAllCerts|setHostnameVerifier\\s*\\(\\s*\\(.*\\)\\s*->\\s*true"));

    @Override
    public String name() {
        return "dangerousCode";
    }

    @Override
    public List<PolicyFinding> evaluate(ProposedChange change) {
        List<PolicyFinding> findings = new ArrayList<>();
        for (FileChange file : change.files()) {
            if (file.isDelete() || !file.path().endsWith(".java")) {
                continue;
            }
            PATTERNS.forEach((label, pattern) -> {
                if (pattern.matcher(file.content()).find()) {
                    findings.add(new PolicyFinding(name(), PolicyVerdict.DENY, label + " in " + file.path()));
                }
            });
        }
        return findings;
    }
}
