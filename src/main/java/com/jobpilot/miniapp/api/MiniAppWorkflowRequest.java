package com.jobpilot.miniapp.api;

import com.jobpilot.jobreview.domain.WorkflowStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The two fields a client may send. The target job comes from the path, and the acting user
 * comes from the validated initData — never from the body.
 *
 * @param status    the requested workflow state
 * @param mutationId a client-generated idempotency key, stable across retries of <em>this</em>
 *     user action and different for every new one. It is what makes an ambiguous timeout
 *     recoverable: re-sending the same id resolves the original operation instead of guessing
 *     whether it committed. Constrained to a short opaque token so it can never carry payload.
 */
public record MiniAppWorkflowRequest(
        @NotNull WorkflowStatus status,
        @NotBlank @Size(min = 8, max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String mutationId) {
}
