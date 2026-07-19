package com.jobpilot.resume.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ApplicationDocumentSelectionRequest(
        @NotNull @Positive Long resumeVersionId,
        @Positive Long coverNoteId) {
}
