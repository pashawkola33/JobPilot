package com.jobpilot.manualurl.domain;

public enum ManualJobStatus {
    CREATED,
    ALREADY_EXISTS,
    UNSUPPORTED_SOURCE,
    INVALID_URL,
    FETCH_FAILED,
    PARSE_FAILED,
    LOCATION_INELIGIBLE,
    EARLY_CAREER_INELIGIBLE,
    BLOCKED_OR_PROTECTED
}
