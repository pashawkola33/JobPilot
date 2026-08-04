package com.jobpilot.matching.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ScoreRescorePreviewPropertiesTest {
    @Test
    void previewIsDisabledByDefaultWithABoundedDatasetLimit() {
        ScoreRescorePreviewProperties properties = new ScoreRescorePreviewProperties(false, null);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.maxInspectedJobs())
                .isEqualTo(ScoreRescorePreviewProperties.DEFAULT_MAX_INSPECTED_JOBS)
                .isLessThanOrEqualTo(ScoreRescorePreviewProperties.HARD_MAX_INSPECTED_JOBS);
    }

    @Test
    void limitCannotExceedTheHardMaximum() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new ScoreRescorePreviewProperties(true,
                        ScoreRescorePreviewProperties.HARD_MAX_INSPECTED_JOBS + 1));
    }
}
