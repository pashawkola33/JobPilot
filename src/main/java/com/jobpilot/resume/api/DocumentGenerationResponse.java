package com.jobpilot.resume.api;

import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.resume.application.DocumentGenerationResult;
import com.jobpilot.resume.application.DocumentGenerationStatus;
import java.util.List;

public record DocumentGenerationResponse(DocumentGenerationStatus status, long jobId,
                                         Long resumeVersionId, Long coverNoteId,
                                         String resumePreview, List<String> changeSummary,
                                         List<String> interviewClaims, boolean fallbackUsed,
                                         LlmFailureCategory fallbackReason) {
    public static DocumentGenerationResponse from(DocumentGenerationResult result) {
        return new DocumentGenerationResponse(result.status(), result.jobId(),
                result.resumeVersionId(), result.coverNoteId(), result.resumePreview(),
                result.changeSummary(), result.interviewClaims(), result.fallbackUsed(),
                result.fallbackReason());
    }
}
