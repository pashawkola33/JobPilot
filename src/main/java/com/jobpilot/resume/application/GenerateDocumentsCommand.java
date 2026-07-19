package com.jobpilot.resume.application;

import com.jobpilot.resume.domain.DocumentFormat;
import java.util.EnumSet;
import java.util.Set;

public record GenerateDocumentsCommand(boolean includeCoverNote,
                                       Set<DocumentFormat> formats,
                                       boolean useLlmDrafting) {
    public GenerateDocumentsCommand {
        if (formats == null || formats.isEmpty()) {
            throw new IllegalArgumentException("At least one document format is required");
        }
        formats = Set.copyOf(EnumSet.copyOf(formats));
    }
}
