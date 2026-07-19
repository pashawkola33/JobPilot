package com.jobpilot.resume.application;

import com.jobpilot.llm.domain.LlmFailureCategory;
import java.util.List;

public record DocumentGenerationResult(
        DocumentGenerationStatus status,
        long jobId,
        Long resumeVersionId,
        Long coverNoteId,
        String resumePreview,
        List<String> changeSummary,
        List<String> interviewClaims,
        boolean fallbackUsed,
        LlmFailureCategory fallbackReason) {
    public DocumentGenerationResult {
        changeSummary = changeSummary == null ? List.of() : List.copyOf(changeSummary);
        interviewClaims = interviewClaims == null ? List.of() : List.copyOf(interviewClaims);
    }

    public static DocumentGenerationResult failure(DocumentGenerationStatus status, long jobId) {
        return new DocumentGenerationResult(status, jobId, null, null, null,
                List.of(), List.of(), false, null);
    }
}
