package com.jobpilot.resume.application;

public enum DocumentGenerationStatus {
    CREATED,
    CACHED,
    FALLBACK,
    DISABLED,
    JOB_NOT_FOUND,
    PROFILE_NOT_FOUND,
    ANALYSIS_FAILED,
    BUDGET_EXCEEDED,
    GENERATION_FAILED,
    RENDER_FAILED,
    ARTIFACT_INVALID,
    STORAGE_FAILED
}
