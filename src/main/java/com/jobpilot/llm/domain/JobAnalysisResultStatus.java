package com.jobpilot.llm.domain;

public enum JobAnalysisResultStatus {
    CREATED,
    CACHED,
    FALLBACK,
    DISABLED,
    BUDGET_EXCEEDED,
    JOB_NOT_FOUND,
    PROFILE_NOT_FOUND,
    PROVIDER_FAILED,
    INVALID_PROVIDER_RESPONSE
}
