package com.agentic.sdlc.api;

import java.util.List;

import com.agentic.sdlc.state.ApprovalRequest;
import com.agentic.sdlc.state.StageRun;
import com.agentic.sdlc.state.WorkflowRun;

public record RunView(WorkflowRun run, List<StageRun> stages, List<ApprovalRequest> pendingApprovals) {
}
