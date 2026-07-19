package com.jobpilot.llm.provider;

import java.util.Optional;

public record LlmHttpResponse(int statusCode, String body, String retryAfter) {
    public Optional<String> retryAfterValue() {
        return Optional.ofNullable(retryAfter).filter(value -> !value.isBlank());
    }
}
