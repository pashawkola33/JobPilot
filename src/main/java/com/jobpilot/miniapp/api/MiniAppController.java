package com.jobpilot.miniapp.api;

import com.jobpilot.jobreview.application.JobReviewException;
import com.jobpilot.jobreview.application.JobReviewService;
import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.miniapp.application.MiniAppSnapshotService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The whole Mini App surface: read a snapshot, change one job's workflow state.
 *
 * <p>Mounted under its own prefix so a reverse proxy can publish {@code /api/mini-app/**}
 * without ever exposing {@code /internal/**}. Authentication and the allow-list are handled
 * by MiniAppAuthInterceptor before any method here runs.
 */
@RestController
@Validated
@RequestMapping(MiniAppController.BASE)
public class MiniAppController {
    public static final String BASE = "/api/mini-app/v1";

    private final MiniAppSnapshotService snapshots;
    private final JobReviewService review;

    public MiniAppController(MiniAppSnapshotService snapshots, JobReviewService review) {
        this.snapshots = snapshots;
        this.review = review;
    }

    @GetMapping("/snapshot")
    public MiniAppSnapshot snapshot() {
        return snapshots.snapshot();
    }

    /**
     * Idempotent: the existing service reports {@code changed=false} when the vacancy is
     * already in the requested state, and transition rules stay in JobReviewService.
     */
    @PutMapping("/jobs/{jobId}/workflow")
    public MiniAppWorkflowResponse workflow(@PathVariable @Positive long jobId,
                                            @Valid @RequestBody MiniAppWorkflowRequest request) {
        WorkflowView view = switch (request.status()) {
            case SAVED -> review.save(jobId);
            case APPLIED -> review.applied(jobId);
            case DISMISSED -> review.dismiss(jobId);
            case UNREVIEWED -> review.reset(jobId);
        };
        return new MiniAppWorkflowResponse(view.jobId(), view.status().name(), view.changed(),
                view.updatedAt());
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

    /** The authoritative state after the change, so the client can drop its optimistic guess. */
    public record MiniAppWorkflowResponse(long jobId, String status, boolean changed,
                                          Instant updatedAt) {
    }
}
