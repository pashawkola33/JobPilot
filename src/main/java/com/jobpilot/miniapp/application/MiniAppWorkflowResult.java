package com.jobpilot.miniapp.application;

import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.miniapp.api.MiniAppSnapshot;

/** The authoritative workflow outcome and the read model produced in the same transaction. */
public record MiniAppWorkflowResult(
        WorkflowView workflow,
        boolean changed,
        MiniAppSnapshot snapshot) {
}
