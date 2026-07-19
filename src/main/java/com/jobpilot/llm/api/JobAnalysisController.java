package com.jobpilot.llm.api;

import com.jobpilot.llm.application.JobAnalysisService;
import com.jobpilot.llm.domain.JobAnalysisResult;
import com.jobpilot.llm.domain.JobAnalysisResultStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/jobs")
public class JobAnalysisController {
    private final JobAnalysisService service;

    public JobAnalysisController(JobAnalysisService service) {
        this.service = service;
    }

    @PostMapping("/{jobId}/analysis")
    public ResponseEntity<JobAnalysisResponse> analyze(
            @PathVariable long jobId,
            @RequestParam(defaultValue = "true") boolean candidateSpecific) {
        JobAnalysisResult result = service.analyze(jobId, candidateSpecific);
        return ResponseEntity.status(status(result.status())).body(JobAnalysisResponse.from(result));
    }

    private HttpStatus status(JobAnalysisResultStatus status) {
        return switch (status) {
            case CREATED -> HttpStatus.CREATED;
            case JOB_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PROFILE_NOT_FOUND -> HttpStatus.CONFLICT;
            default -> HttpStatus.OK;
        };
    }
}
