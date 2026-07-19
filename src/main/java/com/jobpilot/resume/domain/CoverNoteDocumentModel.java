package com.jobpilot.resume.domain;

import java.util.List;

/** Canonical, provider-neutral and renderer-neutral cover-note content. */
public record CoverNoteDocumentModel(
        String candidateName,
        DocumentContactBlock contact,
        String roleTitle,
        String company,
        String salutation,
        List<String> paragraphs,
        String closing,
        List<String> referencedCandidateFactKeys,
        List<String> referencedVacancyEvidence,
        String templateVersion) {
    public CoverNoteDocumentModel {
        paragraphs = List.copyOf(paragraphs);
        referencedCandidateFactKeys = List.copyOf(referencedCandidateFactKeys);
        referencedVacancyEvidence = List.copyOf(referencedVacancyEvidence);
    }

    public String plainText() {
        return salutation + "\n\n" + String.join("\n\n", paragraphs)
                + "\n\n" + closing + "\n" + candidateName;
    }
}
