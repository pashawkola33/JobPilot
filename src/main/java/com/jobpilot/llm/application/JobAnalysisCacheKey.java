package com.jobpilot.llm.application;

import com.jobpilot.common.Hashing;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.llm.domain.LlmOperationType;
import org.springframework.stereotype.Component;

@Component
public class JobAnalysisCacheKey {
    public String jobContentHash(Job job, ExtractedRequirements extracted) {
        return Hashing.sha256(String.join("\u0000", safe(job.getTitle()), safe(job.getCompany()),
                safe(job.getLocation()), safe(job.getDescriptionHash()), extracted.toString()));
    }

    public String cacheKey(String jobHash, CandidateTruthSnapshot truth,
                           LlmOperationType operation, String promptVersion,
                           String provider, String model) {
        return Hashing.sha256(String.join("\u0000", jobHash,
                truth == null ? "generic" : Integer.toString(truth.profileVersion()),
                truth == null ? "generic" : truth.truthHash(), operation.name(),
                promptVersion, normalize(provider), model));
    }

    public String requestKey(String cacheKey, int attempt) {
        return Hashing.sha256(cacheKey + "|attempt=" + attempt);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String normalize(String provider) {
        return safe(provider).strip().toLowerCase(java.util.Locale.ROOT);
    }
}
