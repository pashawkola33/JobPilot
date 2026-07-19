package com.jobpilot.resume.application;

import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.resume.domain.DocumentGenerationMethod;

public record DocumentDraftOutcome<T>(T plan, DocumentGenerationMethod method,
                                      boolean fallbackUsed,
                                      LlmFailureCategory failureCategory) {
    public static <T> DocumentDraftOutcome<T> provider(T value) {
        return new DocumentDraftOutcome<>(value, DocumentGenerationMethod.PROVIDER, false, null);
    }

    public static <T> DocumentDraftOutcome<T> fallback(T value, LlmFailureCategory failure) {
        return new DocumentDraftOutcome<>(value, DocumentGenerationMethod.DETERMINISTIC, true, failure);
    }
}
