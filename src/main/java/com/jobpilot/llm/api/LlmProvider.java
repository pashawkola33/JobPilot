package com.jobpilot.llm.api;

public interface LlmProvider {
    LlmResponse execute(LlmRequest request);
}
