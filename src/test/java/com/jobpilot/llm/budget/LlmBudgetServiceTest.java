package com.jobpilot.llm.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.llm.domain.LlmOperationType;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.llm.repository.LlmBudgetReservationRepository;
import com.jobpilot.llm.repository.LlmUsageEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:llm-budget;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "jobpilot.llm.enabled=true", "jobpilot.llm.provider=openai",
        "jobpilot.llm.base-url=https://api.openai.com/v1",
        "jobpilot.llm.api-key=obviously-fake-secret", "jobpilot.llm.model=model-a",
        "jobpilot.llm.connect-timeout=1s", "jobpilot.llm.response-timeout=2s",
        "jobpilot.llm.max-input-tokens=1000", "jobpilot.llm.max-output-tokens=500",
        "jobpilot.llm.max-retries=0", "jobpilot.llm.request-budget-usd=0.00200000",
        "jobpilot.llm.daily-budget-usd=0.00400000",
        "jobpilot.llm.monthly-budget-usd=0.00600000",
        "jobpilot.llm.input-cost-per-million-tokens=1",
        "jobpilot.llm.output-cost-per-million-tokens=2"
})
@Import(LlmBudgetServiceTest.MutableClockConfiguration.class)
class LlmBudgetServiceTest {
    @Autowired private LlmBudgetService budget;
    @Autowired private LlmBudgetReservationRepository reservations;
    @Autowired private LlmUsageEventRepository usage;
    @Autowired private JobAnalysisRepository analyses;
    @Autowired private JobRepository jobs;
    @Autowired private MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    private Job job;

    @BeforeEach
    void setUp() {
        usage.deleteAll();
        analyses.deleteAll();
        reservations.deleteAll();
        jobs.deleteAll();
        clock.set(Instant.parse("2026-07-19T10:00:00Z"));
        job = jobs.save(job("budget"));
    }

    @Test
    void exactDailyBoundaryIsAllowedAndOneReservationOverIsRejected() {
        assertThat(reserve(1).decision()).isEqualTo(LlmBudgetDecision.RESERVED);
        assertThat(reserve(2).decision()).isEqualTo(LlmBudgetDecision.RESERVED);
        assertThat(reserve(3).decision()).isEqualTo(LlmBudgetDecision.DAILY_LIMIT);
        assertThat(reservations.committedForDay(java.time.LocalDate.parse("2026-07-19")))
                .isEqualByComparingTo("0.00400000");
    }

    @Test
    void persistedDuplicateDoesNotReserveTwiceAcrossServiceCalls() {
        LlmBudgetReservationResult first = reserve(1);
        LlmBudgetReservationResult replay = reserve(1);

        assertThat(replay.decision()).isEqualTo(LlmBudgetDecision.DUPLICATE);
        assertThat(replay.reservation().getId()).isEqualTo(first.reservation().getId());
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    void concurrentReservationsSerializeAgainstTheDatabaseBudgetLock() throws Exception {
        int requests = 3;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(requests)) {
            List<Future<LlmBudgetReservationResult>> futures = java.util.stream.IntStream
                    .rangeClosed(1, requests)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return reserve(index);
                    })).toList();
            start.countDown();
            List<LlmBudgetDecision> decisions = futures.stream().map(this::get)
                    .map(LlmBudgetReservationResult::decision).toList();

            assertThat(decisions).containsExactlyInAnyOrder(
                    LlmBudgetDecision.RESERVED, LlmBudgetDecision.RESERVED,
                    LlmBudgetDecision.DAILY_LIMIT);
            assertThat(reservations.count()).isEqualTo(2);
        }
    }

    @Test
    void reconciliationReleasesUnusedAmountAndChargesAboveEstimateConservatively() {
        LlmBudgetReservation below = reserve(1).reservation();
        reconcile(below.getId(), new BigDecimal("0.00100000"));
        assertThat(reservations.findById(below.getId()).orElseThrow().getFinalCostUsd())
                .isEqualByComparingTo("0.00100000");
        assertThat(reservations.committedForDay(java.time.LocalDate.parse("2026-07-19")))
                .isEqualByComparingTo("0.00100000");

        LlmBudgetReservation above = reserve(2).reservation();
        reconcile(above.getId(), new BigDecimal("0.00300000"));
        assertThat(reservations.findById(above.getId()).orElseThrow().getFinalCostUsd())
                .isEqualByComparingTo("0.00300000");
        assertThat(reservations.committedForDay(java.time.LocalDate.parse("2026-07-19")))
                .isEqualByComparingTo("0.00400000");
        assertThat(reserve(3).decision()).isEqualTo(LlmBudgetDecision.DAILY_LIMIT);
    }

    @Test
    void expiredNeverStartedReservationIsReleasedWithoutFakeTimeoutUsage() {
        LlmBudgetReservation stale = reserve(1).reservation();
        clock.set(Instant.parse("2026-07-19T10:03:00Z"));

        assertThat(reserve(2).decision()).isEqualTo(LlmBudgetDecision.RESERVED);
        LlmBudgetReservation reloaded = reservations.findById(stale.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LlmBudgetReservationStatus.RELEASED);
        assertThat(reloaded.getFinalCostUsd()).isZero();
        assertThat(usage.existsByReservationId(stale.getId())).isFalse();
    }

    @Test
    void expiredStartedReservationKeepsConservativeAmbiguousDeliveryAccounting() {
        LlmBudgetReservation stale = reserve(1).reservation();
        budget.markProviderStarted(stale.getId());
        clock.set(Instant.parse("2026-07-19T10:03:00Z"));

        assertThat(reserve(2).decision()).isEqualTo(LlmBudgetDecision.RESERVED);
        LlmBudgetReservation reloaded = reservations.findById(stale.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(LlmBudgetReservationStatus.ABANDONED);
        assertThat(reloaded.getFinalCostUsd()).isEqualByComparingTo(reloaded.getReservedCostUsd());
        assertThat(usage.existsByReservationId(stale.getId())).isTrue();
        assertThat(usage.findAll()).anySatisfy(event -> {
            assertThat(event.isTokenCountEstimated()).isTrue();
            assertThat(event.getInputTokens()).isPositive();
            assertThat(event.getOutputTokens()).isPositive();
        });
    }

    @Test
    void dailyAndMonthlyBudgetsResetOnUtcBoundaries() {
        clock.set(Instant.parse("2026-07-31T23:59:00Z"));
        assertThat(reserve(1).reserved()).isTrue();
        assertThat(reserve(2).reserved()).isTrue();
        clock.set(Instant.parse("2026-08-01T00:01:00Z"));
        assertThat(reserve(3).reserved()).isTrue();
        assertThat(reserve(4).reserved()).isTrue();
        assertThat(reserve(5).decision()).isEqualTo(LlmBudgetDecision.DAILY_LIMIT);
    }

    @Test
    void monthlyLimitSpansMultipleUtcDaysAndAllowsTheExactBoundary() {
        LlmBudgetReservation first = reserve(1).reservation();
        LlmBudgetReservation second = reserve(2).reservation();
        reconcile(first.getId(), first.getReservedCostUsd());
        reconcile(second.getId(), second.getReservedCostUsd());
        clock.set(Instant.parse("2026-07-20T00:01:00Z"));
        assertThat(reserve(3).decision()).isEqualTo(LlmBudgetDecision.RESERVED);
        assertThat(reserve(4).decision()).isEqualTo(LlmBudgetDecision.MONTHLY_LIMIT);
        assertThat(reservations.committedForMonth(java.time.LocalDate.parse("2026-07-01")))
                .isEqualByComparingTo("0.00600000");
    }

    private LlmBudgetReservationResult reserve(int suffix) {
        return budget.reserve(job, LlmOperationType.JOB_ANALYSIS,
                Integer.toHexString(suffix).repeat(64).substring(0, 64));
    }

    private void reconcile(long id, BigDecimal cost) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> budget.reconcileWithinTransaction(id, cost));
    }

    private LlmBudgetReservationResult get(Future<LlmBudgetReservationResult> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private Job job(String id) {
        return new Job("synthetic", id, "https://example.invalid/jobs/" + id,
                "Synthetic Intern", "Synthetic Company", "Synthetic City", RemoteType.ONSITE,
                null, "Synthetic Java internship", null, null, "a".repeat(64),
                "b".repeat(64), id + "-fingerprint", clock.instant());
    }

    @TestConfiguration
    static class MutableClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.EPOCH);

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only");
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
