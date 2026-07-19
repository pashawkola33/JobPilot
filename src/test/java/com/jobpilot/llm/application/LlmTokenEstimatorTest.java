package com.jobpilot.llm.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LlmTokenEstimatorTest {
    private final LlmTokenEstimator estimator = new LlmTokenEstimator();

    @ParameterizedTest
    @ValueSource(strings = {
            "A concise English vacancy description.",
            "后端工程实习生需要编写可靠服务",
            "😀🚀🧑‍💻🔐📦",
            "public static void main(String[] args){for(;;){x>>>=1;}}"
    })
    void estimatesEnglishCjkEmojiAndDenseSourceConservatively(String value) {
        long estimated = estimator.conservativeEstimate(value);

        assertThat(estimated).isGreaterThanOrEqualTo(
                (long) value.getBytes(StandardCharsets.UTF_8).length);
        assertThat(estimated).isGreaterThan(value.codePoints().count());
    }
}
