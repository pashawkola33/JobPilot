package com.jobpilot.resume.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentPropertiesTest {
    private static final String HMAC_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void concurrentCompletionTimeoutDefaultsToFifteenSeconds() {
        assertThat(withConcurrentCompletionTimeout(null).concurrentCompletionTimeout())
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(DocumentProperties.disabled().concurrentCompletionTimeout())
                .isEqualTo(Duration.ofSeconds(15));
    }

    /** The default has to survive a graceful shutdown phase, which is twenty seconds. */
    @Test
    void theDefaultStaysInsideTheGracefulShutdownWindow() {
        assertThat(DocumentProperties.disabled().concurrentCompletionTimeout())
                .isLessThan(Duration.ofSeconds(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT1S", "PT15S", "PT2M"})
    void acceptsTimeoutsInsideTheSafeBounds(String duration) {
        assertThat(withConcurrentCompletionTimeout(Duration.parse(duration))
                .concurrentCompletionTimeout()).isEqualTo(Duration.parse(duration));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0.999S", "PT0S", "PT2M0.001S", "PT10M"})
    void rejectsTimeoutsOutsideTheSafeBounds(String duration) {
        assertThatThrownBy(() -> withConcurrentCompletionTimeout(Duration.parse(duration)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Document limits are outside their safe bounds");
    }

    /** staleAfter keeps its own, much longer window; the two must not be confused. */
    @Test
    void staleAfterKeepsItsOwnSeparateBounds() {
        assertThat(withConcurrentCompletionTimeout(Duration.ofSeconds(15)).staleAfter())
                .isEqualTo(Duration.ofMinutes(10));
        assertThatThrownBy(() -> properties(Duration.ofSeconds(30), Duration.ofSeconds(15)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DocumentProperties withConcurrentCompletionTimeout(Duration timeout) {
        return properties(Duration.ofMinutes(10), timeout);
    }

    private static DocumentProperties properties(Duration staleAfter, Duration concurrentTimeout) {
        return new DocumentProperties(true, Path.of("data/documents"), 2_097_152, 2_097_152,
                "resume-v1", "cover-v1", "renderer-v1", 4_000, staleAfter, concurrentTimeout,
                HMAC_KEY, new DocumentProperties.Contact(
                "student@example.test", "", "", "", ""));
    }
}
