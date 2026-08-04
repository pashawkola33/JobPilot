package com.jobpilot.matching.rescore;

import com.jobpilot.matching.preview.ScoreRescorePreviewResult.ErrorCategory;

/** Safe result for immutable plan construction. */
public record ScoreRescorePlanResult(
        Status status,
        ErrorCategory errorCategory,
        String safeMessage,
        ScoreRescorePlan plan) {

    public enum Status { SUCCESS, ERROR }

    public static ScoreRescorePlanResult success(ScoreRescorePlan plan) {
        return new ScoreRescorePlanResult(Status.SUCCESS, null, null, plan);
    }

    public static ScoreRescorePlanResult error(ErrorCategory category, String message) {
        return new ScoreRescorePlanResult(Status.ERROR, category, message, null);
    }
}
