package com.jobpilot.llm.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.llm.domain.LlmOperationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobAnalysisCacheKeyTest {
    private final JobAnalysisCacheKey keys = new JobAnalysisCacheKey();

    @Test
    void changesForJobProfilePromptProviderAndModelButIsStableForIdenticalInput() {
        ExtractedRequirements requirements = requirements();
        String firstJobHash = keys.jobContentHash(job("a"), requirements);
        String changedJobHash = keys.jobContentHash(job("b"), requirements);
        CandidateTruthSnapshot v1 = truth(1, "c");
        CandidateTruthSnapshot v2 = truth(2, "d");
        String baseline = keys.cacheKey(firstJobHash, v1, LlmOperationType.JOB_ANALYSIS,
                "prompt-v1", "provider-a", "model-a");

        assertThat(keys.cacheKey(firstJobHash, v1, LlmOperationType.JOB_ANALYSIS,
                "prompt-v1", "PROVIDER-A", "model-a")).isEqualTo(baseline);
        assertThat(keys.cacheKey(changedJobHash, v1, LlmOperationType.JOB_ANALYSIS,
                "prompt-v1", "provider-a", "model-a")).isNotEqualTo(baseline);
        assertThat(keys.cacheKey(firstJobHash, v2, LlmOperationType.JOB_ANALYSIS,
                "prompt-v1", "provider-a", "model-a")).isNotEqualTo(baseline);
        assertThat(keys.cacheKey(firstJobHash, v1, LlmOperationType.JOB_ANALYSIS,
                "prompt-v2", "provider-a", "model-a")).isNotEqualTo(baseline);
        assertThat(keys.cacheKey(firstJobHash, v1, LlmOperationType.JOB_ANALYSIS,
                "prompt-v1", "provider-a", "model-b")).isNotEqualTo(baseline);
        assertThat(keys.cacheKey(firstJobHash, v1, LlmOperationType.JOB_ANALYSIS,
                "prompt-v1", "provider-b", "model-a")).isNotEqualTo(baseline);
        assertThat(keys.requestKey(baseline, 1)).isEqualTo(keys.requestKey(baseline, 1));
        assertThat(keys.requestKey(baseline, 2)).isNotEqualTo(keys.requestKey(baseline, 1));
    }

    private Job job(String descriptionHashPrefix) {
        return new Job("synthetic", "1", "https://example.invalid/jobs/1",
                "Synthetic Intern", "Synthetic Company", "Synthetic City", RemoteType.ONSITE,
                null, "Synthetic Java internship", null, null, "e".repeat(64),
                descriptionHashPrefix.repeat(64), "fingerprint", Instant.EPOCH);
    }

    private CandidateTruthSnapshot truth(int version, String hash) {
        return new CandidateTruthSnapshot((long) version, version, hash.repeat(64),
                "Synthetic City", "Synthetic BSc", true, false, BigDecimal.ZERO, List.of());
    }

    private ExtractedRequirements requirements() {
        return new ExtractedRequirements("INTERNSHIP", true, null, null, false,
                List.of("Java"), List.of("Java"), List.of(), "Synthetic City",
                null, List.of(), null, null, null, "DETERMINISTIC");
    }
}
