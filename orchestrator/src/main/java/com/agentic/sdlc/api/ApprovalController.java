package com.agentic.sdlc.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agentic.sdlc.audit.AuditService;
import com.agentic.sdlc.common.NotFoundException;
import com.agentic.sdlc.engine.WorkflowEngine;
import com.agentic.sdlc.state.ApprovalRequest;
import com.agentic.sdlc.state.ApprovalRequestRepository;
import com.agentic.sdlc.state.ApprovalStatus;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final WorkflowEngine engine;
    private final ApprovalRequestRepository approvals;

    public ApprovalController(WorkflowEngine engine, ApprovalRequestRepository approvals) {
        this.engine = engine;
        this.approvals = approvals;
    }

    /** The reviewer's inbox across all runs. */
    @GetMapping
    public List<ApprovalRequest> pending() {
        return approvals.findByStatusOrderByIdAsc(ApprovalStatus.PENDING);
    }

    @PostMapping("/{approvalId}/approve")
    public ApprovalRequest approve(@PathVariable Long approvalId, @Valid @RequestBody ApiRequests.Decide request) {
        engine.approve(approvalId, AuditService.humanActor(request.actor()), request.comment());
        return reload(approvalId);
    }

    /** For CLARIFICATION requests, put the answers in {@code comment}; the stage re-runs with them. */
    @PostMapping("/{approvalId}/reject")
    public ApprovalRequest reject(@PathVariable Long approvalId, @Valid @RequestBody ApiRequests.Decide request) {
        engine.reject(approvalId, AuditService.humanActor(request.actor()), request.comment());
        return reload(approvalId);
    }

    private ApprovalRequest reload(Long approvalId) {
        return approvals.findById(approvalId).orElseThrow(() -> new NotFoundException("Unknown approval " + approvalId));
    }
}
