package com.jobpilot.matching;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import java.util.Objects;

/** Side-effect-free extraction and scoring result shared by ingestion and diagnostics. */
public record ScoreCalculation(ExtractedRequirements requirements, ScoreCard score) {
    public ScoreCalculation {
        Objects.requireNonNull(requirements, "Extracted requirements are required");
        Objects.requireNonNull(score, "Score card is required");
    }

    /** Component total before hard-blocker handling and the final 0..100 clamp. */
    public int rawComponentTotal() {
        return score.formalEligibility()
                + score.javaBackend()
                + score.traineeQuality()
                + score.supportingTechnology()
                + score.locationFormat()
                + score.experienceCompatibility()
                - score.penalties();
    }
}
