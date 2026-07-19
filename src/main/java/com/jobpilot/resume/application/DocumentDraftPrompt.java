package com.jobpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;

public record DocumentDraftPrompt(String trustedInstructions, String candidateFactsJson,
                                  String vacancyDataJson, JsonNode schema) {
}
