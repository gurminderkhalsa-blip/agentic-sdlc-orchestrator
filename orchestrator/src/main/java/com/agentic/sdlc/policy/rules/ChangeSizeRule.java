package com.agentic.sdlc.policy.rules;

import java.util.List;

import org.springframework.stereotype.Component;

import com.agentic.sdlc.config.SdlcProperties;
import com.agentic.sdlc.policy.PolicyFinding;
import com.agentic.sdlc.policy.PolicyRule;
import com.agentic.sdlc.policy.PolicyVerdict;
import com.agentic.sdlc.policy.ProposedChange;
import com.agentic.sdlc.workspace.FileChange;

/** Change control: one attempt may not touch more files or lines than a human can reasonably review. */
@Component
public class ChangeSizeRule implements PolicyRule {

    private final int maxFiles;
    private final int maxLines;

    public ChangeSizeRule(SdlcProperties properties) {
        this.maxFiles = properties.policy().maxFilesPerChange();
        this.maxLines = properties.policy().maxLinesPerChange();
    }

    @Override
    public String name() {
        return "changeSize";
    }

    @Override
    public List<PolicyFinding> evaluate(ProposedChange change) {
        int lines = change.files().stream().mapToInt(ChangeSizeRule::lines).sum();
        if (change.files().size() > maxFiles || lines > maxLines) {
            return List.of(new PolicyFinding(name(), PolicyVerdict.DENY, change.files().size() + " files / " + lines
                    + " lines exceeds the limit of " + maxFiles + " files / " + maxLines + " lines; make a smaller change"));
        }
        return List.of();
    }

    private static int lines(FileChange file) {
        return file.isDelete() ? 0 : (int) file.content().lines().count();
    }
}
