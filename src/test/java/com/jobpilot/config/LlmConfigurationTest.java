package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LlmConfigurationTest {
    @Test
    void disabledConfigurationRequiresNoProviderSecretsOrPricing() {
        JobPilotProperties.Llm disabled = JobPilotProperties.Llm.disabled();

        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.apiKey()).isEmpty();
        assertThat(disabled.model()).isEmpty();
    }

    @Test
    void enabledConfigurationFailsClosedWithoutEveryRequiredValue() {
        assertThatThrownBy(() -> enabled("", "model-a", "https://api.openai.com/v1",
                1_000, 500, money("0.01"), money("1"), money("10")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("obviously-fake-secret");
        assertThatThrownBy(() -> enabled("obviously-fake-secret", "", "https://api.openai.com/v1",
                1_000, 500, money("0.01"), money("1"), money("10")))
                .hasMessageNotContaining("obviously-fake-secret");
    }

    @Test
    void rejectsUnsafeProviderModelUrlTokenAndMoneyValuesWithoutEchoingKey() {
        assertInvalid("other", "model-a", "https://api.openai.com/v1", 1_000, 500,
                money("0.01"), money("1"), money("10"));
        assertInvalid("openai", "bad model", "https://api.openai.com/v1", 1_000, 500,
                money("0.01"), money("1"), money("10"));
        assertInvalid("openai", "model-a", "http://api.openai.com/v1?key=value", 1_000, 500,
                money("0.01"), money("1"), money("10"));
        assertInvalid("openai", "model-a", "https://api.openai.com/v1", 0, 500,
                money("0.01"), money("1"), money("10"));
        assertInvalid("openai", "model-a", "https://api.openai.com/v1", 1_000, 0,
                money("0.01"), money("1"), money("10"));
        assertInvalid("openai", "model-a", "https://api.openai.com/v1", 1_000, 500,
                BigDecimal.ZERO, money("1"), money("10"));
        assertInvalid("openai", "model-a", "https://api.openai.com/v1", 1_000, 500,
                money("2"), money("1"), money("10"));
        assertInvalid("openai", "model-a", "https://api.openai.com/v1", 1_000, 500,
                money("0.01"), money("1"), new BigDecimal("1000000.00000001"));
    }

    @Test
    void permitsOnlyTheExactOfficialOpenAiHostAndVersionOneBasePath() {
        assertThat(enabled("obviously-fake-secret", "model-a",
                "https://API.OPENAI.COM/v1/", 1_000, 500,
                money("0.01"), money("1"), money("10")).responsesEndpoint())
                .hasToString("https://API.OPENAI.COM/v1/responses");

        for (String invalid : new String[] {
                "https://localhost/v1", "https://127.0.0.1/v1", "https://10.0.0.1/v1",
                "https://169.254.169.254/v1", "https://api.openai.com.example/v1",
                "https://user:pass@api.openai.com/v1", "http://api.openai.com/v1",
                "https://api.openai.com/v1?target=elsewhere",
                "https://api.openai.com/v1#fragment", "https://api.openai.com:444/v1",
                "https://api.openai.com/other"
        }) {
            assertInvalid("openai", "model-a", invalid, 1_000, 500,
                    money("0.01"), money("1"), money("10"));
        }
    }

    @Test
    void rejectsExcessiveMaximumRetryExposureAtConfigurationTime() {
        assertThatThrownBy(() -> new JobPilotProperties.Llm(true, "openai",
                "https://api.openai.com/v1", "obviously-fake-secret", "model-a",
                Duration.ofSeconds(5), Duration.ofSeconds(60), 1_000_000, 128_000, 3,
                money("1000000"), money("1000000"), money("1000000"),
                money("1000000"), money("1000000")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("obviously-fake-secret");
    }

    private void assertInvalid(String provider, String model, String baseUrl,
                               Integer maxInput, Integer maxOutput,
                               BigDecimal request, BigDecimal daily, BigDecimal monthly) {
        assertThatThrownBy(() -> new JobPilotProperties.Llm(true, provider, baseUrl,
                "obviously-fake-secret", model, Duration.ofSeconds(5), Duration.ofSeconds(60),
                maxInput, maxOutput, 1, request, daily, monthly, money("1"), money("2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("obviously-fake-secret");
    }

    private JobPilotProperties.Llm enabled(String key, String model, String baseUrl,
                                           Integer maxInput, Integer maxOutput,
                                           BigDecimal request, BigDecimal daily,
                                           BigDecimal monthly) {
        return new JobPilotProperties.Llm(true, "openai", baseUrl, key, model,
                Duration.ofSeconds(5), Duration.ofSeconds(60), maxInput, maxOutput, 1,
                request, daily, monthly, money("1"), money("2"));
    }

    private BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
