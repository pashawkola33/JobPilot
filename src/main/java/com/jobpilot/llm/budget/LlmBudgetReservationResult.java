package com.jobpilot.llm.budget;

import java.math.BigDecimal;

public record LlmBudgetReservationResult(
        LlmBudgetDecision decision,
        LlmBudgetReservation reservation,
        BigDecimal estimatedMaximumCostUsd) {
    public boolean reserved() {
        return decision == LlmBudgetDecision.RESERVED;
    }
}
