package com.jobpilot.miniapp.api;

import com.jobpilot.applications.application.ApplicationTrackingException;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.jobreview.application.JobReviewException;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.miniapp.application.MiniAppMutationException;
import com.jobpilot.miniapp.application.MiniAppOperation;
import com.jobpilot.miniapp.application.MiniAppSnapshotService;
import com.jobpilot.miniapp.application.MiniAppWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The whole Mini App surface: read a snapshot, change one job's workflow state, undo a change.
 *
 * <p>Mounted under its own prefix so a reverse proxy can publish {@code /api/mini-app/**}
 * without ever exposing {@code /internal/**}. Authentication and the allow-list are handled by
 * MiniAppAuthInterceptor before any method here runs.
 *
 * <p>The two write endpoints return an <strong>operation result</strong>, not a snapshot. That
 * asymmetry is the P0-B contract: a mutation is authoritative about the job it locked and about
 * nothing else, so global projections may only be replaced by {@link #snapshot()}. See
 * docs/mini-app-p0b-consistency-model.md.
 */
@RestController
@Validated
@RequestMapping(MiniAppController.BASE)
public class MiniAppController {
    public static final String BASE = "/api/mini-app/v1";

    private final MiniAppSnapshotService snapshots;
    private final MiniAppWorkflowService workflows;

    public MiniAppController(MiniAppSnapshotService snapshots, MiniAppWorkflowService workflows) {
        this.snapshots = snapshots;
        this.workflows = workflows;
    }

    /** The only authoritative source of global state, and the only one the client reconciles from. */
    @GetMapping("/snapshot")
    public MiniAppSnapshot snapshot() {
        return snapshots.snapshot();
    }

    /**
     * Applies one workflow change. Re-sending the same {@code mutationId} resolves the original
     * operation rather than performing a second one, which is what makes an ambiguous timeout
     * recoverable without ever guessing whether the first attempt committed.
     */
    @PutMapping("/jobs/{jobId}/workflow")
    public MiniAppOperationResponse workflow(@PathVariable @Positive long jobId,
                                             @Valid @RequestBody MiniAppWorkflowRequest request) {
        return MiniAppOperationResponse.of(
                workflows.change(request.mutationId(), jobId, request.status()));
    }

    /** Server-owned reversal. The client supplies a capability, never a state to restore. */
    @PostMapping("/undo")
    public MiniAppOperationResponse undo(@Valid @RequestBody MiniAppUndoRequest request) {
        return MiniAppOperationResponse.of(
                workflows.undo(request.mutationId(), request.undoToken()));
    }

    /** Expected protocol outcomes, not faults — each one a typed 409 the client can act on. */
    @ExceptionHandler(MiniAppMutationException.class)
    public ResponseEntity<MiniAppApiError> mutationFailure(MiniAppMutationException failure) {
        String category = switch (failure.getCategory()) {
            case IDEMPOTENCY_CONFLICT -> MiniAppApiError.IDEMPOTENCY_CONFLICT;
            case UNDO_STALE -> MiniAppApiError.UNDO_STALE;
        };
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MiniAppApiError(category, failure.getMessage()));
    }

    @ExceptionHandler(ApplicationTrackingException.class)
    public ResponseEntity<MiniAppApiError> applicationFailure(ApplicationTrackingException failure) {
        HttpStatus status = switch (failure.getCategory()) {
            case JOB_NOT_FOUND, APPLICATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_TRANSITION, CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_VALUE -> HttpStatus.BAD_REQUEST;
        };
        String category = status == HttpStatus.NOT_FOUND
                ? MiniAppApiError.JOB_NOT_FOUND : MiniAppApiError.INVALID_WORKFLOW;
        return ResponseEntity.status(status)
                .body(new MiniAppApiError(category, failure.getMessage()));
    }

    @ExceptionHandler(JobReviewException.class)
    public ResponseEntity<MiniAppApiError> reviewFailure(JobReviewException failure) {
        HttpStatus status = switch (failure.getCategory()) {
            case JOB_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_WORKFLOW -> HttpStatus.CONFLICT;
        };
        String category = switch (failure.getCategory()) {
            case JOB_NOT_FOUND -> MiniAppApiError.JOB_NOT_FOUND;
            case INVALID_WORKFLOW -> MiniAppApiError.INVALID_WORKFLOW;
        };
        return ResponseEntity.status(status)
                .body(new MiniAppApiError(category, failure.getMessage()));
    }

    /**
     * What one mutation authoritatively decided — about its own job only.
     *
     * <p>There is deliberately no {@code snapshot} field. P0-A had one, and it was the bug: a
     * mutation commits with a revision assigned at commit while its transaction snapshot may
     * predate an ingestion or Telegram write a concurrent GET already returned, so accepting its
     * global view because the revision looked newer would erase genuinely newer state.
     */
    public record MiniAppOperationResponse(String mutationId, long mutationRevision,
                                           boolean replayed, long jobId, WorkflowStatus status,
                                           boolean changed, ApplicationStatus applicationStatus,
                                           String undoToken) {
        static MiniAppOperationResponse of(MiniAppOperation operation) {
            return new MiniAppOperationResponse(operation.mutationId(),
                    operation.mutationRevision(), operation.replayed(), operation.jobId(),
                    operation.status(), operation.changed(), operation.applicationStatus(),
                    operation.undoToken());
        }
    }
}
