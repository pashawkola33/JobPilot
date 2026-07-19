package com.jobpilot.llm.domain;

public record EvidenceReference(EvidenceSource source, String sourceKey, String excerpt) {
}
