package com.jobpilot.resume.application;

import com.jobpilot.resume.domain.DocumentGenerationMethod;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import java.time.Instant;
import java.util.List;

public record ResumeMetadataView(long id, long jobId, int profileVersion,
                                 Long sourceAnalysisId, String selectedTitle,
                                 String summary, String plainTextPreview,
                                 List<String> changeSummary, List<String> interviewClaims,
                                 String templateVersion, DocumentGenerationMethod generationMethod,
                                 boolean fallbackUsed, DocumentRenderStatus renderStatus,
                                 boolean docxAvailable, String docxSha256, Long docxSize,
                                 boolean pdfAvailable, String pdfSha256, Long pdfSize,
                                 Integer pdfPageCount,
                                 Instant generatedAt) {
}
