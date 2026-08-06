package com.jobpilot.miniapp.api;

import com.jobpilot.jobreview.domain.WorkflowStatus;
import jakarta.validation.constraints.NotNull;

/**
 * The only field a client may send. The target job comes from the path, and the acting user
 * comes from the validated initData — never from the body.
 */
public record MiniAppWorkflowRequest(@NotNull WorkflowStatus status) {
}
