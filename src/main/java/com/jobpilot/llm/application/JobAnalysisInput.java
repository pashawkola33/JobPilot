package com.jobpilot.llm.application;

import com.jobpilot.jobs.domain.ExtractedRequirements;

public record JobAnalysisInput(
        Long jobId,
        String title,
        String company,
        String location,
        String description,
        String jobContentHash,
        ExtractedRequirements deterministicRequirements,
        CandidateTruthSnapshot candidateTruth) {
    public String vacancyEvidenceText() {
        return String.join("\n", safe(title), safe(company), safe(location), safe(description));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
