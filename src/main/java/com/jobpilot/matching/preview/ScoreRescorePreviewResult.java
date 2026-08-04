package com.jobpilot.matching.preview;

/** Success or a safe, categorized fail-closed outcome. */
public record ScoreRescorePreviewResult(
        Status status,
        ErrorCategory errorCategory,
        String safeMessage,
        ScoreRescorePreviewReport report) {

    public enum Status { SUCCESS, ERROR }

    public enum ErrorCategory {
        INVALID_LIMIT,
        CAP_EXCEEDED,
        MISSING_REQUIRED_DATA,
        INCONSISTENT_PERSISTED_DATA,
        CALCULATION_FAILED,
        INTERNAL_ERROR
    }

    public static ScoreRescorePreviewResult success(ScoreRescorePreviewReport report) {
        return new ScoreRescorePreviewResult(Status.SUCCESS, null, null, report);
    }

    public static ScoreRescorePreviewResult error(ErrorCategory category, String safeMessage) {
        return new ScoreRescorePreviewResult(Status.ERROR, category, safeMessage, null);
    }
}
