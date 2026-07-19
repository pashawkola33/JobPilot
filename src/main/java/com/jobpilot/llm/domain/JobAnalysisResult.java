package com.jobpilot.llm.domain;

public record JobAnalysisResult(
        JobAnalysisResultStatus status,
        Long analysisId,
        Long jobId,
        Integer candidateProfileVersion,
        JobAnalysisData analysis,
        LlmFailureCategory failureCategory) {
}
