package com.jobpilot.llm.application;

public class JobAnalysisValidationException extends RuntimeException {
    public JobAnalysisValidationException(String safeMessage) {
        super(safeMessage, null, false, false);
    }
}
