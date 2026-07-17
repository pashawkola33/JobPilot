package com.jobpilot.matching;

import java.util.List;

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
}
