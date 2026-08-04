package com.jobpilot.matching;

import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Pure calculation boundary. It deliberately owns no repository and mutates no job state.
 */
@Component
public class JobScoreCalculator {
    private final DeterministicRequirementExtractor extractor;
    private final JobMatchingService matching;

    public JobScoreCalculator(DeterministicRequirementExtractor extractor,
                              JobMatchingService matching) {
        this.extractor = extractor;
        this.matching = matching;
    }

    public ScoreCalculation calculate(Job job) {
        Objects.requireNonNull(job, "Job is required");
        return calculate(job, extractor.extract(job));
    }

    /** Supports diagnostic counterfactuals while retaining the production scoring rules. */
    public ScoreCalculation calculate(Job job, ExtractedRequirements requirements) {
        Objects.requireNonNull(job, "Job is required");
        Objects.requireNonNull(requirements, "Extracted requirements are required");
        return new ScoreCalculation(requirements, matching.score(job, requirements));
    }
}
