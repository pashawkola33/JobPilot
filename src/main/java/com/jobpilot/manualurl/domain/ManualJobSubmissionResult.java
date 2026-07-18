package com.jobpilot.manualurl.domain;

import java.util.List;

public record ManualJobSubmissionResult(
        ManualJobStatus status,
        Long jobId,
        String canonicalUrl,
        Integer score,
        List<String> strengths,
        List<String> risks,
        String message) {

    public ManualJobSubmissionResult {
        strengths = strengths == null ? List.of() : List.copyOf(strengths);
        risks = risks == null ? List.of() : List.copyOf(risks);
    }

    public static ManualJobSubmissionResult failure(ManualJobStatus status, String message) {
        return new ManualJobSubmissionResult(status, null, null, null, List.of(), List.of(), message);
    }
}
