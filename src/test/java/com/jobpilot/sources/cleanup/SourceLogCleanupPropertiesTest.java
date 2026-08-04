package com.jobpilot.sources.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SourceLogCleanupPropertiesTest {
    @Test
    void modeAndBoundsDefaultToSafeValues() {
        SourceLogCleanupProperties properties =
                new SourceLogCleanupProperties(null, null, null, null, null);

        assertThat(properties.mode()).isEqualTo(SourceLogCleanupProperties.Mode.OFF);
        assertThat(properties.minimumAge()).isEqualTo(Duration.ofHours(6));
        assertThat(properties.maxCandidates()).isEqualTo(20);
    }

    @Test
    void guardsAreSortedAndOptionalCountMustMatch() {
        SourceLogCleanupProperties properties = new SourceLogCleanupProperties(
                SourceLogCleanupProperties.Mode.PREVIEW, Duration.ofHours(6), 20,
                "100, 69,74", "3");

        assertThat(properties.guards().expectedRunningIds()).containsExactly(69L, 74L, 100L);
        assertThat(properties.guards().expectedRunningCount()).isEqualTo(3);
    }

    @Test
    void malformedDuplicateMissingAndOverLimitGuardsFailClosed() {
        assertThatThrownBy(() -> properties("69,69", null, 20).guards())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("", null, 20).guards())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("69", "2", 20).guards())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties("69", null, 101).guards())
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SourceLogCleanupProperties properties(String ids, String count, int max) {
        return new SourceLogCleanupProperties(SourceLogCleanupProperties.Mode.PREVIEW,
                Duration.ofHours(6), max, ids, count);
    }
}
