package com.jobpilot.llm.application;

import com.fasterxml.jackson.databind.JsonNode;

public record JobAnalysisPrompt(
        String trustedInstructions,
        String candidateFactsJson,
        String vacancyDataJson,
        JsonNode outputSchema,
        long estimatedInputTokens) {
}
