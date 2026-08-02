package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HttpConfigurationTest {
    @Test
    void rejectsUnsafeTimeoutAndResponseBounds() {
        assertInvalid(Duration.ZERO, Duration.ofSeconds(2), 1_024);
        assertInvalid(Duration.ofSeconds(31), Duration.ofSeconds(40), 1_024);
        assertInvalid(Duration.ofSeconds(2), Duration.ofSeconds(2), 1_024);
        assertInvalid(Duration.ofSeconds(1), Duration.ofSeconds(91), 1_024);
        assertInvalid(Duration.ofSeconds(1), Duration.ofSeconds(2), 1_023);
        assertInvalid(Duration.ofSeconds(1), Duration.ofSeconds(2), 10 * 1_024 * 1_024 + 1);
    }

    private void assertInvalid(Duration connect, Duration response, int bytes) {
        assertThatThrownBy(() -> new JobPilotProperties.Http(connect, response, bytes))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
