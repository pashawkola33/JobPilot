package com.jobpilot.manualurl.api;

import com.jobpilot.manualurl.domain.ManualJobStatus;
import com.jobpilot.manualurl.domain.ManualJobSubmissionResult;
import java.util.List;

public record ManualJobUrlResponse(
        ManualJobStatus status,
        Long jobId,
        String canonicalUrl,
        Integer score,
        List<String> strengths,
        List<String> risks,
        String message) {

    public static ManualJobUrlResponse from(ManualJobSubmissionResult result) {
        return new ManualJobUrlResponse(result.status(), result.jobId(), result.canonicalUrl(),
                result.score(), result.strengths(), result.risks(), result.message());
    }
}
