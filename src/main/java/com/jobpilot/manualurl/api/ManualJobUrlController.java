package com.jobpilot.manualurl.api;

import com.jobpilot.manualurl.application.ManualJobUrlService;
import com.jobpilot.manualurl.domain.ManualJobStatus;
import com.jobpilot.manualurl.domain.ManualJobSubmissionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/jobs")
public class ManualJobUrlController {
    private final ManualJobUrlService service;

    public ManualJobUrlController(ManualJobUrlService service) {
        this.service = service;
    }

    @PostMapping("/manual-url")
    public ResponseEntity<ManualJobUrlResponse> submit(@RequestBody(required = false) ManualJobUrlRequest request) {
        ManualJobSubmissionResult result = service.submit(request == null ? null : request.url());
        return ResponseEntity.status(httpStatus(result.status())).body(ManualJobUrlResponse.from(result));
    }

    private HttpStatus httpStatus(ManualJobStatus status) {
        return switch (status) {
            case CREATED -> HttpStatus.CREATED;
            case ALREADY_EXISTS -> HttpStatus.OK;
            case INVALID_URL -> HttpStatus.BAD_REQUEST;
            case BLOCKED_OR_PROTECTED -> HttpStatus.FORBIDDEN;
            case UNSUPPORTED_SOURCE, PARSE_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case FETCH_FAILED -> HttpStatus.BAD_GATEWAY;
        };
    }
}
