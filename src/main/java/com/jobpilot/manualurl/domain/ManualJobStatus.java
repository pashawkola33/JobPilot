package com.jobpilot.manualurl.domain;

public enum ManualJobStatus {
    CREATED,
    ALREADY_EXISTS,
    UNSUPPORTED_SOURCE,
    INVALID_URL,
    FETCH_FAILED,
    PARSE_FAILED,
    BLOCKED_OR_PROTECTED
}
