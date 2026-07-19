package com.jobpilot.llm.domain;

import java.util.List;

public record JobAnalysisData(
        String roleSummary,
        List<String> mustHaveRequirements,
        List<String> preferredRequirements,
        List<String> responsibilities,
        String experienceRequirement,
        String educationRequirement,
        String languageRequirement,
        String locationConstraints,
        String workAuthorizationSignals,
        List<CandidateStrength> candidateStrengths,
        List<String> candidateGaps,
        List<String> ambiguousRequirements,
        List<EvidenceReference> evidenceReferences,
        int confidenceScore,
        boolean deterministicFallbackUsed) {
}
