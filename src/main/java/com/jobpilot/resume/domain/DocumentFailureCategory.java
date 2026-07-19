package com.jobpilot.resume.domain;

public enum DocumentFailureCategory {
    CONFIGURATION,
    DRAFT_FAILED,
    TRUTH_VALIDATION,
    RENDER_FAILED,
    ARTIFACT_INVALID,
    STORAGE_FAILED,
    DATABASE_FAILED,
    STALE_GENERATION
}
