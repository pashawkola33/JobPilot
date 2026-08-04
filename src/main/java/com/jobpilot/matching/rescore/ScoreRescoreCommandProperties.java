package com.jobpilot.matching.rescore;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Default-off command settings. Write guards deliberately have no defaults. */
@ConfigurationProperties("jobpilot.score-rescore-command")
public record ScoreRescoreCommandProperties(
        Mode mode,
        boolean writeEnabled,
        String expectedChangedCount,
        String expectedPlanFingerprint,
        String maxJobs,
        String confirmation) {

    public enum Mode { OFF, PREVIEW, WRITE }

    @ConstructorBinding
    public ScoreRescoreCommandProperties {
        mode = mode == null ? Mode.OFF : mode;
    }
}
