package com.jobpilot.llm.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.llm.domain.LlmOperationType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:llm-request-cap;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "jobpilot.llm.enabled=true", "jobpilot.llm.provider=openai",
        "jobpilot.llm.base-url=https://api.openai.com/v1",
        "jobpilot.llm.api-key=obviously-fake-secret", "jobpilot.llm.model=model-a",
        "jobpilot.llm.max-input-tokens=1000", "jobpilot.llm.max-output-tokens=500",
        "jobpilot.llm.request-budget-usd=0.00199999",
        "jobpilot.llm.daily-budget-usd=1", "jobpilot.llm.monthly-budget-usd=2",
        "jobpilot.llm.input-cost-per-million-tokens=1",
        "jobpilot.llm.output-cost-per-million-tokens=2"
})
class LlmRequestBudgetTest {
    @Autowired private LlmBudgetService budget;
    @Autowired private JobRepository jobs;

    @Test
    void rejectsARequestOneMinimumCostUnitOverTheRequestCap() {
        Job job = jobs.save(new Job("synthetic", "request-cap",
                "https://example.invalid/jobs/request-cap", "Synthetic Intern",
                "Synthetic Company", "Synthetic City", RemoteType.ONSITE, null,
                "Synthetic Java internship", null, null, "a".repeat(64),
                "b".repeat(64), "request-cap-fingerprint", Instant.EPOCH));

        LlmBudgetReservationResult result = budget.reserve(
                job, LlmOperationType.JOB_ANALYSIS, "f".repeat(64));

        assertThat(result.decision()).isEqualTo(LlmBudgetDecision.REQUEST_LIMIT);
        assertThat(result.estimatedMaximumCostUsd()).isEqualByComparingTo("0.00400000");
    }
}
