package com.jobpilot.browser.api;

/** Typed worker outcomes. Unknown values from the worker are rejected. */
public enum BrowserExtractionStatus {
    EXTRACTED,
    INSUFFICIENT_DATA,
    UNSUPPORTED,
    BLOCKED,
    AUTH_REQUIRED,
    CHALLENGE_DETECTED,
    RATE_LIMITED,
    TIMEOUT,
    INVALID_URL,
    FETCH_FAILED,
    /** The worker could not be reached or returned an unusable response. */
    WORKER_UNAVAILABLE
}
