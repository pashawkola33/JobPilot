package com.jobpilot.matching;

import java.util.List;
import java.util.Objects;

public record ScoreCard(
        int score,
        ScoreBand band,
        boolean suitable,
        int formalEligibility,
        int javaBackend,
        int traineeQuality,
        int supportingTechnology,
        int locationFormat,
        int experienceCompatibility,
        int freshness,
        int penalties,
        List<String> strengths,
        List<String> risks,
        List<String> hardBlockers) {

    static final String FRESHNESS_STRENGTH =
            "Vacancy is recent and appears open";

    /**
     * Candidate/job fit equality. Freshness is observable metadata rather than semantic fit,
     * including the explanatory strength derived solely from that freshness bucket.
     */
    public boolean semanticEquals(ScoreCard other) {
        if (other == null) return false;
        return score == other.score
                && band == other.band
                && suitable == other.suitable
                && formalEligibility == other.formalEligibility
                && javaBackend == other.javaBackend
                && traineeQuality == other.traineeQuality
                && supportingTechnology == other.supportingTechnology
                && locationFormat == other.locationFormat
                && experienceCompatibility == other.experienceCompatibility
                && penalties == other.penalties
                && Objects.equals(semanticStrengths(), other.semanticStrengths())
                && Objects.equals(risks, other.risks)
                && Objects.equals(hardBlockers, other.hardBlockers);
    }

    public List<String> semanticStrengths() {
        return strengths.stream()
                .filter(value -> !FRESHNESS_STRENGTH.equals(value))
                .toList();
    }
}
