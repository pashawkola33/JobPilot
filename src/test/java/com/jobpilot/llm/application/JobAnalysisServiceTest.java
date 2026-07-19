package com.jobpilot.llm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobProcessor;
import com.jobpilot.llm.api.LlmProvider;
import com.jobpilot.llm.api.LlmProviderException;
import com.jobpilot.llm.api.LlmResponse;
import com.jobpilot.llm.budget.LlmBudgetReservationStatus;
import com.jobpilot.llm.budget.LlmBudgetService;
import com.jobpilot.llm.domain.CandidateMatchStrength;
import com.jobpilot.llm.domain.CandidateStrength;
import com.jobpilot.llm.domain.EvidenceReference;
import com.jobpilot.llm.domain.EvidenceSource;
import com.jobpilot.llm.domain.JobAnalysisData;
import com.jobpilot.llm.domain.JobAnalysisResultStatus;
import com.jobpilot.llm.domain.LlmFailureCategory;
import com.jobpilot.llm.domain.LlmUsageStatus;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.llm.repository.LlmBudgetReservationRepository;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:llm-analysis;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "jobpilot.llm.enabled=true", "jobpilot.llm.provider=openai",
        "jobpilot.llm.base-url=https://api.openai.com/v1",
        "jobpilot.llm.api-key=obviously-fake-secret", "jobpilot.llm.model=model-a",
        "jobpilot.llm.connect-timeout=1s", "jobpilot.llm.response-timeout=2s",
        "jobpilot.llm.max-input-tokens=100000", "jobpilot.llm.max-output-tokens=2000",
        "jobpilot.llm.max-retries=1", "jobpilot.llm.request-budget-usd=1",
        "jobpilot.llm.daily-budget-usd=10", "jobpilot.llm.monthly-budget-usd=100",
        "jobpilot.llm.input-cost-per-million-tokens=1",
        "jobpilot.llm.output-cost-per-million-tokens=2"
})
class JobAnalysisServiceTest {
    @Autowired private JobAnalysisService service;
    @Autowired private JobProcessor processor;
    @Autowired private JobRepository jobs;
    @Autowired private JobRequirementRepository requirements;
    @Autowired private JobScoreRepository scores;
    @SpyBean private JobAnalysisRepository analyses;
    @Autowired private LlmBudgetReservationRepository reservations;
    @Autowired private LlmUsageEventRepository usage;
    @Autowired private LlmBudgetService budget;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper mapper;
    @MockBean private LlmProvider provider;

    @BeforeEach
    void setUp() {
        reset(provider, analyses);
        usage.deleteAll();
        analyses.deleteAll();
        reservations.deleteAll();
        requirements.deleteAll();
        scores.deleteAll();
        jobs.deleteAll();
    }

    @Test
    void commitsReservationBeforeProviderOutsideTransactionThenCachesValidatedResult() {
        long jobId = createJob("success", description()).getId();
        AtomicBoolean noTransaction = new AtomicBoolean();
        when(provider.execute(any())).thenAnswer(invocation -> {
            noTransaction.set(!TransactionSynchronizationManager.isActualTransactionActive());
            assertThat(reservations.findAll()).singleElement().satisfies(reservation ->
                    assertThat(reservation.getStatus()).isEqualTo(LlmBudgetReservationStatus.RESERVED));
            return new LlmResponse(validJson(), 120L, 40L);
        });

        var created = service.analyze(jobId, true);
        var cached = service.analyze(jobId, true);

        assertThat(created.status()).isEqualTo(JobAnalysisResultStatus.CREATED);
        assertThat(created.analysis().deterministicFallbackUsed()).isFalse();
        assertThat(cached.status()).isEqualTo(JobAnalysisResultStatus.CACHED);
        assertThat(cached.analysisId()).isEqualTo(created.analysisId());
        assertThat(noTransaction).isTrue();
        assertThat(reservations.findAll()).singleElement().satisfies(reservation -> {
            assertThat(reservation.getStatus()).isEqualTo(LlmBudgetReservationStatus.SETTLED);
            assertThat(reservation.getFinalCostUsd()).isLessThan(reservation.getReservedCostUsd());
        });
        assertThat(usage.findAll()).singleElement().satisfies(event -> {
            assertThat(event.isTokenCountEstimated()).isFalse();
            assertThat(event.getInputTokens()).isEqualTo(120);
            assertThat(event.isFallbackUsed()).isFalse();
        });
        verify(provider, times(1)).execute(any());
    }

    @Test
    void missingUsageUsesNonZeroConservativeEstimate() {
        long jobId = createJob("missing-usage", description()).getId();
        when(provider.execute(any())).thenReturn(new LlmResponse(validJson(), null, null));

        assertThat(service.analyze(jobId, true).status()).isEqualTo(JobAnalysisResultStatus.CREATED);

        assertThat(usage.findAll()).singleElement().satisfies(event -> {
            assertThat(event.isTokenCountEstimated()).isTrue();
            assertThat(event.getInputTokens()).isEqualTo(100000);
            assertThat(event.getOutputTokens()).isEqualTo(2000);
            assertThat(event.getEstimatedCostUsd()).isPositive();
        });
    }

    @Test
    void transientAttemptThenSuccessAddsOneConservativeAttemptToFinalUsage() {
        long jobId = createJob("retry-success", description()).getId();
        when(provider.execute(any())).thenReturn(
                new LlmResponse(validJson(), 100L, 30L, 2, 1));

        assertThat(service.analyze(jobId, true).status()).isEqualTo(JobAnalysisResultStatus.CREATED);

        assertThat(usage.findAll()).singleElement().satisfies(event -> {
            assertThat(event.isTokenCountEstimated()).isTrue();
            assertThat(event.getInputTokens()).isEqualTo(100_100L);
            assertThat(event.getOutputTokens()).isEqualTo(2_030L);
            assertThat(event.getEstimatedCostUsd()).isPositive();
        });
        assertThat(reservations.findAll()).singleElement().satisfies(reservation -> {
            assertThat(reservation.getFinalCostUsd()).isPositive();
            assertThat(reservation.getFinalCostUsd()).isLessThan(reservation.getReservedCostUsd());
        });
    }

    @Test
    void invalidOrUntruthfulOutputFallsBackAndIsNeverCachedAsProviderSuccess() {
        long jobId = createJob("invalid", description()).getId();
        when(provider.execute(any())).thenReturn(new LlmResponse(
                "{\"roleSummary\":\"invented incomplete object\"}", 80L, 20L));

        var first = service.analyze(jobId, true);
        var cooldownReplay = service.analyze(jobId, true);

        assertThat(first.status()).isEqualTo(JobAnalysisResultStatus.INVALID_PROVIDER_RESPONSE);
        assertThat(first.analysis().deterministicFallbackUsed()).isTrue();
        assertThat(cooldownReplay.status()).isEqualTo(JobAnalysisResultStatus.INVALID_PROVIDER_RESPONSE);
        assertThat(analyses.findAll()).singleElement().satisfies(analysis ->
                assertThat(analysis.getStatus().name()).isEqualTo("FALLBACK"));
        verify(provider, times(1)).execute(any());
    }

    @Test
    void timeoutAfterDeliveryChargesAConservativeAttemptWithoutMultiplyingRetries() {
        long jobId = createJob("timeout", description()).getId();
        when(provider.execute(any())).thenThrow(new LlmProviderException(
                LlmFailureCategory.TIMEOUT, "Synthetic timeout"));

        var result = service.analyze(jobId, true);

        assertThat(result.status()).isEqualTo(JobAnalysisResultStatus.PROVIDER_FAILED);
        assertThat(result.failureCategory()).isEqualTo(LlmFailureCategory.TIMEOUT);
        assertThat(result.analysis().deterministicFallbackUsed()).isTrue();
        assertThat(reservations.findAll()).singleElement().satisfies(reservation ->
                assertThat(reservation.getFinalCostUsd()).isPositive()
                        .isLessThan(reservation.getReservedCostUsd()));
        assertThat(usage.findAll()).singleElement().satisfies(event -> {
            assertThat(event.isTokenCountEstimated()).isTrue();
            assertThat(event.isFallbackUsed()).isTrue();
            assertThat(event.getInputTokens()).isEqualTo(100_000L);
        });
    }

    @Test
    void exhaustedRetriesKeepTheCompleteReservationExposure() {
        long jobId = createJob("retry-exhausted", description()).getId();
        when(provider.execute(any())).thenThrow(new LlmProviderException(
                LlmFailureCategory.PROVIDER_ERROR, "Synthetic exhausted retries", 2));

        assertThat(service.analyze(jobId, true).status())
                .isEqualTo(JobAnalysisResultStatus.PROVIDER_FAILED);

        assertThat(reservations.findAll()).singleElement().satisfies(reservation ->
                assertThat(reservation.getFinalCostUsd())
                        .isEqualByComparingTo(reservation.getReservedCostUsd()));
        assertThat(usage.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getInputTokens()).isEqualTo(200_000L);
            assertThat(event.getOutputTokens()).isEqualTo(4_000L);
        });
    }

    @Test
    void lateSuccessReconcilesTheExistingUsageEventIdempotently() {
        long jobId = createJob("late-success", description()).getId();
        when(provider.execute(any())).thenAnswer(invocation -> {
            long reservationId = reservations.findAll().getFirst().getId();
            new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> budget.abandonWithinTransaction(reservationId));
            assertThat(usage.count()).isEqualTo(1);
            return new LlmResponse(validJson(), 100L, 30L);
        });

        var created = service.analyze(jobId, true);
        var cached = service.analyze(jobId, true);

        assertThat(created.status()).isEqualTo(JobAnalysisResultStatus.CREATED);
        assertThat(cached.status()).isEqualTo(JobAnalysisResultStatus.CACHED);
        assertThat(reservations.findAll()).singleElement().satisfies(reservation -> {
            assertThat(reservation.getStatus()).isEqualTo(LlmBudgetReservationStatus.LATE_SETTLED);
            assertThat(reservation.getFinalCostUsd())
                    .isEqualByComparingTo(reservation.getReservedCostUsd());
        });
        assertThat(usage.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getStatus()).isEqualTo(LlmUsageStatus.SUCCEEDED);
            assertThat(event.isFallbackUsed()).isFalse();
            assertThat(event.getFailureCategory()).isNull();
        });
        verify(provider, times(1)).execute(any());
    }

    @Test
    void concurrentIdenticalRequestsProduceAtMostOneProviderCall() throws Exception {
        long jobId = createJob("concurrent", description()).getId();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(provider.execute(any())).thenAnswer(invocation -> {
            providerEntered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new LlmResponse(validJson(), 100L, 30L);
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.analyze(jobId, true));
            assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.analyze(jobId, true));
            var concurrent = second.get(5, TimeUnit.SECONDS);
            release.countDown();
            var created = first.get(5, TimeUnit.SECONDS);

            assertThat(created.status()).isEqualTo(JobAnalysisResultStatus.CREATED);
            assertThat(concurrent.status()).isEqualTo(JobAnalysisResultStatus.FALLBACK);
            assertThat(concurrent.failureCategory()).isEqualTo(LlmFailureCategory.CONCURRENT_REQUEST);
            verify(provider, times(1)).execute(any());
            assertThat(reservations.count()).isEqualTo(1);
        }
    }

    @Test
    void changedJobContentCreatesANewCacheEntryAndPaidCall() {
        var job = createJob("changed", description());
        when(provider.execute(any())).thenReturn(new LlmResponse(validJson(), 100L, 30L));
        assertThat(service.analyze(job.getId(), true).status())
                .isEqualTo(JobAnalysisResultStatus.CREATED);

        processor.process(raw("changed", description() + " Kubernetes is now required."));
        assertThat(service.analyze(job.getId(), true).status())
                .isEqualTo(JobAnalysisResultStatus.CREATED);

        verify(provider, times(2)).execute(any());
        assertThat(analyses.count()).isEqualTo(2);
        assertThat(reservations.count()).isEqualTo(2);
    }

    @Test
    void analysisStorageFailureIsNeverReportedAsProviderSuccess() {
        long jobId = createJob("store-failure", description()).getId();
        when(provider.execute(any())).thenReturn(new LlmResponse(validJson(), 100L, 30L));
        doThrow(new DataIntegrityViolationException("Synthetic write failure"))
                .when(analyses).save(any());

        var result = service.analyze(jobId, true);

        assertThat(result.status()).isEqualTo(JobAnalysisResultStatus.PROVIDER_FAILED);
        assertThat(result.failureCategory()).isEqualTo(LlmFailureCategory.DATABASE_ERROR);
        assertThat(reservations.findAll()).singleElement().satisfies(reservation ->
                assertThat(reservation.getStatus()).isEqualTo(LlmBudgetReservationStatus.RESERVED));
        assertThat(usage.count()).isZero();
    }

    private com.jobpilot.jobs.domain.Job createJob(String id, String description) {
        return processor.process(raw(id, description)).job();
    }

    private RawJob raw(String id, String description) {
        return new RawJob("synthetic", id, "https://example.invalid/jobs/" + id,
                "Synthetic Java Intern", "Synthetic Company", "Bucharest, Romania",
                description, "INTERN", Instant.parse("2026-07-19T08:00:00Z"), null,
                "Synthetic fixture " + description);
    }

    private String description() {
        return "Java backend internship in Bucharest with mentorship using Spring Boot and SQL.";
    }

    private String validJson() {
        JobAnalysisData data = new JobAnalysisData("Synthetic Java internship",
                List.of("Java", "Spring Boot"), List.of(),
                List.of("Build backend services"), null, null, null,
                "Bucharest, Romania", null,
                List.of(new CandidateStrength("spring-boot", CandidateMatchStrength.MATCH)),
                List.of(), List.of("Work authorization is unknown"),
                List.of(
                        new EvidenceReference(EvidenceSource.VACANCY,
                                "job.description", "Java backend internship"),
                        new EvidenceReference(EvidenceSource.CANDIDATE_SKILL,
                                "spring-boot", "Spring Boot. Verified technical skill")),
                80, false);
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
    }
}
