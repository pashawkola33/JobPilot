package com.jobpilot.resume.api;

import com.jobpilot.resume.application.GenerateDocumentsCommand;
import com.jobpilot.resume.domain.DocumentFormat;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

public record DocumentGenerationRequest(
        @NotNull Boolean includeCoverNote,
        @NotEmpty Set<DocumentFormat> formats,
        @NotNull Boolean useLlmDrafting) {
    public GenerateDocumentsCommand toCommand() {
        return new GenerateDocumentsCommand(includeCoverNote, formats, useLlmDrafting);
    }
}
