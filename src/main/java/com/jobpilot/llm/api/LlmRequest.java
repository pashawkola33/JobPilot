package com.jobpilot.llm.api;

import com.fasterxml.jackson.databind.JsonNode;

public record LlmRequest(
        String trustedInstructions,
        String candidateFactsJson,
        String vacancyDataJson,
        JsonNode outputSchema,
        int maxOutputTokens) {
}
