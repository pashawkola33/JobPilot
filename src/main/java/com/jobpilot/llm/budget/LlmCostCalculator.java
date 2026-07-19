package com.jobpilot.llm.budget;

import com.jobpilot.config.JobPilotProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

@Component
public class LlmCostCalculator {
    public static final int COST_SCALE = 8;
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private final JobPilotProperties.Llm settings;

    public LlmCostCalculator(JobPilotProperties properties) {
        settings = properties.llm();
    }

    public BigDecimal maximumSingleAttemptCost() {
        requireEnabled();
        return cost(settings.maxInputTokens(), settings.maxOutputTokens());
    }

    public BigDecimal maximumReservationCost() {
        requireEnabled();
        return maximumSingleAttemptCost()
                .multiply(BigDecimal.valueOf((long) settings.maxRetries() + 1L))
                .setScale(COST_SCALE, RoundingMode.CEILING);
    }

    public BigDecimal cost(long inputTokens, long outputTokens) {
        requireEnabled();
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("LLM token counts cannot be negative");
        }
        BigDecimal input = BigDecimal.valueOf(inputTokens)
                .multiply(settings.inputCostPerMillionTokens())
                .divide(ONE_MILLION, COST_SCALE, RoundingMode.CEILING);
        BigDecimal output = BigDecimal.valueOf(outputTokens)
                .multiply(settings.outputCostPerMillionTokens())
                .divide(ONE_MILLION, COST_SCALE, RoundingMode.CEILING);
        return input.add(output).setScale(COST_SCALE, RoundingMode.CEILING);
    }

    private void requireEnabled() {
        if (!settings.enabled()) throw new IllegalStateException("LLM cost calculation is disabled");
    }
}
