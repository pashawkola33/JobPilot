package com.jobpilot.llm.api;

import com.jobpilot.llm.domain.LlmFailureCategory;

public class LlmProviderException extends RuntimeException {
    private final LlmFailureCategory category;
    private final int ambiguousAttempts;

    public LlmProviderException(LlmFailureCategory category, String safeMessage) {
        this(category, safeMessage, defaultAmbiguousAttempts(category));
    }

    public LlmProviderException(LlmFailureCategory category, String safeMessage,
                                int ambiguousAttempts) {
        super(safeMessage, null, false, false);
        if (ambiguousAttempts < 0 || ambiguousAttempts > 4) {
            throw new IllegalArgumentException("LLM attempt accounting is outside its safe bound");
        }
        this.category = category;
        this.ambiguousAttempts = ambiguousAttempts;
    }

    public LlmFailureCategory getCategory() {
        return category;
    }

    public int getAmbiguousAttempts() {
        return ambiguousAttempts;
    }

    public LlmProviderException withAdditionalAmbiguousAttempts(int additional) {
        return new LlmProviderException(category, getMessage(),
                Math.addExact(ambiguousAttempts, additional));
    }

    private static int defaultAmbiguousAttempts(LlmFailureCategory category) {
        return category == LlmFailureCategory.DISABLED
                || category == LlmFailureCategory.CONFIGURATION
                || category == LlmFailureCategory.CALL_LIMIT ? 0 : 1;
    }
}
