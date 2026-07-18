package com.jobpilot.llm.domain;

public enum LlmFailureCategory {
    DISABLED,
    CONFIGURATION,
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_ERROR,
    MALFORMED_RESPONSE,
    SCHEMA_VALIDATION,
    CALL_LIMIT,
    BUDGET_EXHAUSTED,
    UNKNOWN
}
