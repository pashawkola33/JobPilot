package com.jobpilot.llm.api;

public record LlmResponse(String structuredJson, Long inputTokens, Long outputTokens,
                          int physicalAttempts, int ambiguousAttemptsBeforeFinal) {
    public LlmResponse {
        if (physicalAttempts < 1 || ambiguousAttemptsBeforeFinal < 0
                || ambiguousAttemptsBeforeFinal >= physicalAttempts) {
            throw new IllegalArgumentException("LLM response attempt metadata is invalid");
        }
    }

    public LlmResponse(String structuredJson, Long inputTokens, Long outputTokens) {
        this(structuredJson, inputTokens, outputTokens, 1, 0);
    }

    public boolean hasTokenUsage() {
        return inputTokens != null && outputTokens != null;
    }
}
