package com.jobpilot.miniapp.api;

import com.jobpilot.applications.api.ApplicationFollowUpRequest;
import com.jobpilot.applications.api.ApplicationNoteRequest;
import com.jobpilot.applications.api.ApplicationStatusRequest;
import com.jobpilot.applications.application.*;
import com.jobpilot.identity.*;
import com.jobpilot.jobreview.application.JobReviewException;
import com.jobpilot.miniapp.application.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api/mini-app/v2")
public class CandidateWorkflowController {
    private final CandidateWorkflowService service;
    public CandidateWorkflowController(CandidateWorkflowService service) { this.service=service; }
    private AuthenticatedCandidate principal(HttpServletRequest request) { return (AuthenticatedCandidate)request.getAttribute(IdentityWebBoundary.PRINCIPAL); }
    @GetMapping("/snapshot") public MiniAppSnapshot snapshot(HttpServletRequest request) { return service.snapshot(principal(request)); }
    @PutMapping("/jobs/{jobId}/workflow")
    public MiniAppController.MiniAppOperationResponse workflow(HttpServletRequest http,@PathVariable @Positive long jobId,@Valid @RequestBody MiniAppWorkflowRequest request) {
        return MiniAppController.MiniAppOperationResponse.of(service.change(principal(http),request.mutationId(),jobId,request.status()));
    }
    @PostMapping("/undo")
    public MiniAppController.MiniAppOperationResponse undo(HttpServletRequest http,@Valid @RequestBody MiniAppUndoRequest request) {
        return MiniAppController.MiniAppOperationResponse.of(service.undo(principal(http),request.mutationId(),request.undoToken()));
    }
    @GetMapping("/applications/{applicationId}")
    public ApplicationView application(HttpServletRequest http,@PathVariable @Positive long applicationId) { return service.application(principal(http),applicationId); }
    @GetMapping("/jobs/{jobId}/application")
    public ApplicationView applicationForJob(HttpServletRequest http,@PathVariable @Positive long jobId) { return service.applicationForJob(principal(http),jobId); }
    @GetMapping("/applications/{applicationId}/history")
    public List<ApplicationHistoryView> history(HttpServletRequest http,@PathVariable @Positive long applicationId) { return service.history(principal(http),applicationId); }
    @PutMapping("/applications/{applicationId}/notes")
    public ApplicationMutationResult notes(HttpServletRequest http,@PathVariable @Positive long applicationId,@Valid @RequestBody ApplicationNoteRequest request) { return service.notes(principal(http),applicationId,request.notes()); }
    @PutMapping("/applications/{applicationId}/follow-up")
    public ApplicationMutationResult followUp(HttpServletRequest http,@PathVariable @Positive long applicationId,@Valid @RequestBody ApplicationFollowUpRequest request) { return service.followUp(principal(http),applicationId,request.date()); }
    @PutMapping("/applications/{applicationId}/status")
    public ApplicationMutationResult status(HttpServletRequest http,@PathVariable @Positive long applicationId,@Valid @RequestBody ApplicationStatusRequest request) { return service.status(principal(http),applicationId,request.status(),request.interviewAt(),request.rejectionReason()); }
    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String,String>> denied(IdentityException error) { return ResponseEntity.status(error.status()).body(Map.of("category",error.category())); }
    @ExceptionHandler(MiniAppMutationException.class)
    public ResponseEntity<MiniAppApiError> mutation(MiniAppMutationException error) {
        return ResponseEntity.status(409).body(new MiniAppApiError(error.getCategory().name(),error.getMessage()));
    }
    @ExceptionHandler(ApplicationTrackingException.class)
    public ResponseEntity<MiniAppApiError> applicationFailure(ApplicationTrackingException error) {
        int status=switch(error.getCategory()) {case JOB_NOT_FOUND,APPLICATION_NOT_FOUND->404;case INVALID_TRANSITION,CONFLICT->409;case INVALID_VALUE->400;};
        return ResponseEntity.status(status).body(new MiniAppApiError(error.getCategory().name(),error.getMessage()));
    }
    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<MiniAppApiError> concurrentWrite() {
        return ResponseEntity.status(409).body(new MiniAppApiError("CONFLICT","Application changed concurrently. Please retry."));
    }
    @ExceptionHandler(JobReviewException.class)
    public ResponseEntity<MiniAppApiError> reviewFailure(JobReviewException error) { return ResponseEntity.status(error.getCategory()==JobReviewException.Category.JOB_NOT_FOUND?404:409).body(new MiniAppApiError(error.getCategory().name(),error.getMessage())); }
}
