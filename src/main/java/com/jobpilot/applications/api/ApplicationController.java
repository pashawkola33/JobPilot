package com.jobpilot.applications.api;

import com.jobpilot.applications.application.ApplicationHistoryView;
import com.jobpilot.applications.application.ApplicationMutationResult;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.application.ApplicationTrackingException;
import com.jobpilot.applications.application.ApplicationView;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/internal/v1/applications")
public class ApplicationController {
    private final ApplicationTrackerService tracker;

    public ApplicationController(ApplicationTrackerService tracker) {
        this.tracker = tracker;
    }

    @PutMapping("/{jobId}/status")
    public ApplicationMutationResult status(@PathVariable @Positive long jobId,
                                            @Valid @RequestBody ApplicationStatusRequest request) {
        return tracker.transition(jobId, request.status(), request.interviewAt(),
                request.rejectionReason(), ApplicationStatusChangeSource.INTERNAL);
    }

    @PutMapping("/{jobId}/follow-up")
    public ApplicationMutationResult followUp(@PathVariable @Positive long jobId,
                                              @Valid @RequestBody ApplicationFollowUpRequest request) {
        return tracker.changeFollowUpDate(jobId, request.date());
    }

    @PutMapping("/{jobId}/notes")
    public ApplicationMutationResult notes(@PathVariable @Positive long jobId,
                                           @Valid @RequestBody ApplicationNoteRequest request) {
        return tracker.changeNotes(jobId, request.notes());
    }

    @GetMapping("/{jobId}")
    public ApplicationView get(@PathVariable @Positive long jobId) {
        return tracker.findByJobId(jobId);
    }

    @GetMapping("/{jobId}/history")
    public List<ApplicationHistoryView> history(@PathVariable @Positive long jobId) {
        return tracker.history(jobId);
    }

    @GetMapping
    public List<ApplicationView> list(@RequestParam(required = false) ApplicationStatus status) {
        return tracker.list(status);
    }

    @ExceptionHandler(ApplicationTrackingException.class)
    public ResponseEntity<ApplicationApiError> trackingFailure(ApplicationTrackingException failure) {
        HttpStatus status = switch (failure.getCategory()) {
            case JOB_NOT_FOUND, APPLICATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case INVALID_TRANSITION, INVALID_VALUE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(new ApplicationApiError(
                failure.getCategory().name(), failure.getMessage()));
    }
}
