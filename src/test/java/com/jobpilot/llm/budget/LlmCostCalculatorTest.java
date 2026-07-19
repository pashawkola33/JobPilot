package com.jobpilot.llm.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.support.TestProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LlmCostCalculatorTest {
    @Test
    void usesBigDecimalAndExplicitCeilingAtEightDecimalPlaces() {
        JobPilotProperties.Llm llm = new JobPilotProperties.Llm(true, "openai",
                "https://api.openai.com/v1", "obviously-fake-secret", "model-a",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1_000, 500, 0,
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"),
                new BigDecimal("0.33333333"), new BigDecimal("0.66666667"));
        LlmCostCalculator calculator = new LlmCostCalculator(TestProperties.create(llm));

        assertThat(calculator.cost(1, 1)).isEqualByComparingTo("0.00000101");
        assertThat(calculator.cost(1_000, 500)).hasScaleOf(8)
                .isEqualByComparingTo("0.00066668");
    }

    @Test
    void maximumReservationCoversEveryPermittedPhysicalAttemptExactlyOnce() {
        JobPilotProperties.Llm llm = new JobPilotProperties.Llm(true, "openai",
                "https://api.openai.com/v1", "obviously-fake-secret", "model-a",
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1_000, 500, 2,
                new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"),
                new BigDecimal("1"), new BigDecimal("2"));
        LlmCostCalculator calculator = new LlmCostCalculator(TestProperties.create(llm));

        assertThat(calculator.maximumSingleAttemptCost()).isEqualByComparingTo("0.00200000");
        assertThat(calculator.maximumReservationCost()).hasScaleOf(8)
                .isEqualByComparingTo("0.00600000");
    }
}
