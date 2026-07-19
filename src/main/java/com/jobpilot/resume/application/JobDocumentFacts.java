package com.jobpilot.resume.application;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.llm.domain.JobAnalysisData;

/** Detached vacancy/analysis snapshot; vacancy prose remains untrusted data. */
public record JobDocumentFacts(long jobId, String title, String company, String location,
                               String description, String descriptionHash,
                               long analysisId, String analysisCacheKey,
                               JobAnalysisData analysis) {

    public static JobDocumentFacts from(Job job, long analysisId, String analysisCacheKey,
                                        JobAnalysisData data) {
        return new JobDocumentFacts(job.getId(), normalize(job.getTitle(), "the role"),
                normalize(job.getCompany(), "the company"), normalize(job.getLocation(), ""),
                normalize(job.getDescription(), ""), normalize(job.getDescriptionHash(), ""),
                analysisId, analysisCacheKey, data);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
