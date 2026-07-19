package com.jobpilot.resume.application;

import com.jobpilot.applications.domain.ApplicationStatus;

public record ApplicationDocumentSelectionResult(long applicationId, long jobId,
                                                 long resumeVersionId, Long coverNoteId,
                                                 ApplicationStatus applicationStatus,
                                                 boolean changed) {
}
