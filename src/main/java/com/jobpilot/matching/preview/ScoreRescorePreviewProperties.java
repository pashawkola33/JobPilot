package com.jobpilot.matching.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Default-off and deliberately small operational bounds for the one-shot preview. */
@ConfigurationProperties("jobpilot.score-rescore-preview")
public record ScoreRescorePreviewProperties(boolean enabled, Integer maxInspectedJobs) {
    public static final int DEFAULT_MAX_INSPECTED_JOBS = 250;
    public static final int HARD_MAX_INSPECTED_JOBS = 1_000;

    @ConstructorBinding
    public ScoreRescorePreviewProperties {
        maxInspectedJobs = maxInspectedJobs == null
                ? DEFAULT_MAX_INSPECTED_JOBS : maxInspectedJobs;
        if (maxInspectedJobs < 1 || maxInspectedJobs > HARD_MAX_INSPECTED_JOBS) {
            throw new IllegalArgumentException(
                    "Score rescore preview job limit must be between 1 and "
                            + HARD_MAX_INSPECTED_JOBS);
        }
    }
}
