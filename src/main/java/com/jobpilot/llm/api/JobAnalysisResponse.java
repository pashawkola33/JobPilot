package com.jobpilot.llm.api;

import com.jobpilot.llm.domain.JobAnalysisData;
import com.jobpilot.llm.domain.JobAnalysisResult;
import com.jobpilot.llm.domain.JobAnalysisResultStatus;
import com.jobpilot.llm.domain.LlmFailureCategory;

public record JobAnalysisResponse(
        JobAnalysisResultStatus status,
        Long analysisId,
        Long jobId,
        Integer candidateProfileVersion,
        JobAnalysisData analysis,
        LlmFailureCategory failureCategory) {
    public static JobAnalysisResponse from(JobAnalysisResult result) {
        return new JobAnalysisResponse(result.status(), result.analysisId(), result.jobId(),
                result.candidateProfileVersion(), result.analysis(), result.failureCategory());
    }
}
