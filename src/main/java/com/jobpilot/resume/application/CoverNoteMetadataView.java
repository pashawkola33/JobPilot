package com.jobpilot.resume.application;

import com.jobpilot.resume.domain.DocumentGenerationMethod;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import java.time.Instant;
import java.util.List;

public record CoverNoteMetadataView(long id, long jobId, int profileVersion,
                                    long resumeVersionId, Long sourceAnalysisId,
                                    String content, List<String> candidateFactKeys,
                                    String templateVersion,
                                    DocumentGenerationMethod generationMethod,
                                    boolean fallbackUsed, DocumentRenderStatus renderStatus,
                                    boolean docxAvailable, String docxSha256, Long docxSize,
                                    boolean pdfAvailable, String pdfSha256, Long pdfSize,
                                    Integer pdfPageCount,
                                    Instant generatedAt) {
}
