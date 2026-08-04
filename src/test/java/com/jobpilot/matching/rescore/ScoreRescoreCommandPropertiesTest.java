package com.jobpilot.matching.rescore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScoreRescoreCommandPropertiesTest {
    @Test
    void commandAndWriteCapabilityAreDisabledByDefault() {
        ScoreRescoreCommandProperties properties = new ScoreRescoreCommandProperties(
                null, false, null, null, null, null);

        assertThat(properties.mode()).isEqualTo(ScoreRescoreCommandProperties.Mode.OFF);
        assertThat(properties.writeEnabled()).isFalse();
        assertThat(properties.expectedChangedCount()).isNull();
        assertThat(properties.expectedPlanFingerprint()).isNull();
        assertThat(properties.maxJobs()).isNull();
        assertThat(properties.confirmation()).isNull();
    }
}
