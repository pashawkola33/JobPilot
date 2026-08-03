package com.jobpilot.sources.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.jobs.domain.RawJob;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantFetchMonitorTest {
    private final RecordingRecorder recorder = new RecordingRecorder();
    private final TenantFetchMonitor monitor = new TenantFetchMonitor(
            new TenantFailureClassifier(), recorder,
            Clock.fixed(Instant.parse("2026-08-03T11:00:00Z"), ZoneOffset.UTC));

    @AfterEach
    void closeRun() {
        IngestionRunContext.clear();
    }

    @Test
    void aNonEmptyFetchIsRecordedAsSuccessAndReturnsTheJobs() {
        List<RawJob> jobs = monitor.fetch("greenhouse", "acme", () -> List.of(job("1"), job("2")));

        assertThat(jobs).hasSize(2);
        assertThat(recorder.calls).singleElement().satisfies(call -> {
            assertThat(call.status).isEqualTo(TenantAttemptStatus.SUCCESS);
            assertThat(call.provider).isEqualTo("greenhouse");
            assertThat(call.tenant).isEqualTo("acme");
            assertThat(call.fetchedCount).isEqualTo(2);
            assertThat(call.failure.category()).isEqualTo(TenantFailureCategory.NONE);
        });
    }

    @Test
    void anEmptyOrNullFetchIsRecordedAsEmptySuccess() {
        monitor.fetch("lever", "empty", List::of);
        monitor.fetch("lever", "nulls", () -> null);

        assertThat(recorder.calls).hasSize(2);
        assertThat(recorder.calls).allSatisfy(call -> {
            assertThat(call.status).isEqualTo(TenantAttemptStatus.EMPTY_SUCCESS);
            assertThat(call.fetchedCount).isZero();
        });
    }

    @Test
    void aFailingTenantIsClassifiedRecordedAndIsolated() {
        List<RawJob> jobs = monitor.fetch("ashby", "cohere", () -> {
            throw new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, 404);
        });

        assertThat(jobs).isEmpty();
        assertThat(recorder.calls).singleElement().satisfies(call -> {
            assertThat(call.status).isEqualTo(TenantAttemptStatus.FAILURE);
            assertThat(call.failure.category()).isEqualTo(TenantFailureCategory.INVALID_TENANT);
            assertThat(call.failure.httpStatus()).isEqualTo(404);
        });
    }

    @Test
    void oneFailedTenantDoesNotStopTheNextTenant() {
        List<String> attempted = new ArrayList<>();
        List<RawJob> collected = new ArrayList<>();
        for (String tenant : List.of("broken", "healthy", "alsoBroken", "alsoHealthy")) {
            collected.addAll(monitor.fetch("greenhouse", tenant, () -> {
                attempted.add(tenant);
                if (tenant.toLowerCase(java.util.Locale.ROOT).contains("broken")) {
                    throw new ExternalHttpException(ExternalHttpException.Category.IO, null);
                }
                return List.of(job(tenant));
            }));
        }

        assertThat(attempted).containsExactly("broken", "healthy", "alsoBroken", "alsoHealthy");
        assertThat(collected).hasSize(2);
        assertThat(recorder.calls).hasSize(4);
        assertThat(recorder.calls).extracting(call -> call.status).containsExactly(
                TenantAttemptStatus.FAILURE, TenantAttemptStatus.SUCCESS,
                TenantAttemptStatus.FAILURE, TenantAttemptStatus.SUCCESS);
    }

    @Test
    void observabilityPersistenceFailureNeverAbortsOrHidesTheAtsResult() {
        recorder.explode = true;

        List<RawJob> jobs = monitor.fetch("recruitee", "pleo", () -> List.of(job("9")));

        assertThat(jobs).hasSize(1);
        assertThatCode(() -> monitor.fetch("recruitee", "next", () -> {
            throw new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, 500);
        })).doesNotThrowAnyException();
    }

    @Test
    void exactlyOneAttemptRowPerFetchEvenWhenTheProviderReturnsDuplicateJobs() {
        RawJob duplicate = job("same");

        monitor.fetch("lever", "dupes", () -> List.of(duplicate, duplicate, duplicate));

        assertThat(recorder.calls).hasSize(1);
        assertThat(recorder.calls.get(0).fetchedCount).isEqualTo(3);
    }

    @Test
    void attemptsShareTheOpenRunIdAndFeedTheRunSummary() {
        IngestionRunContext run = IngestionRunContext.open();

        monitor.fetch("ashby", "ok", () -> List.of(job("1")));
        monitor.fetch("ashby", "empty", List::of);
        monitor.fetch("ashby", "bad", () -> {
            throw new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, 429);
        });

        assertThat(recorder.calls).extracting(call -> call.runId)
                .containsOnly(run.runId());
        assertThat(run.totalAttempts()).isEqualTo(3);
        assertThat(run.count(TenantAttemptStatus.SUCCESS)).isEqualTo(1);
        assertThat(run.count(TenantAttemptStatus.EMPTY_SUCCESS)).isEqualTo(1);
        assertThat(run.count(TenantAttemptStatus.FAILURE)).isEqualTo(1);
        assertThat(run.failuresByCategory()).containsExactly(
                java.util.Map.entry("RATE_LIMITED", 1));
    }

    @Test
    void withoutAnOpenRunEachAttemptStillGetsAnIdAndIsRecorded() {
        monitor.fetch("ashby", "orphan", () -> List.of(job("1")));

        assertThat(recorder.calls).singleElement()
                .satisfies(call -> assertThat(call.runId).isNotNull());
    }

    @Test
    void theDisabledMonitorStillReturnsJobsAndSwallowsFailures() {
        TenantFetchMonitor disabled = TenantFetchMonitor.disabled();

        assertThat(disabled.fetch("greenhouse", "acme", () -> List.of(job("1")))).hasSize(1);
        assertThat(disabled.fetch("greenhouse", "acme", () -> {
            throw new IllegalStateException("boom");
        })).isEmpty();
    }

    private RawJob job(String id) {
        return new RawJob("fixture", id, "https://example.com/jobs/" + id, "Java Intern",
                "Example", "Bucharest", "Java internship description", null, null, null, "{}");
    }

    private static final class Call {
        private UUID runId;
        private String provider;
        private String tenant;
        private TenantAttemptStatus status;
        private TenantFailure failure;
        private int fetchedCount;
    }

    private static final class RecordingRecorder extends SourceTenantHealthRecorder {
        private final List<Call> calls = new ArrayList<>();
        private boolean explode;

        private RecordingRecorder() {
            super(null, null);
        }

        @Override
        public SourceTenantHealth record(UUID runId, String provider, String tenant,
                                         TenantAttemptStatus status, TenantFailure failure,
                                         int fetchedCount, long durationMs,
                                         Instant startedAt, Instant finishedAt) {
            if (explode) throw new IllegalStateException("database unavailable");
            Call call = new Call();
            call.runId = runId;
            call.provider = provider;
            call.tenant = tenant;
            call.status = status;
            call.failure = failure;
            call.fetchedCount = fetchedCount;
            calls.add(call);
            SourceTenantHealth health = new SourceTenantHealth(provider, tenant, startedAt);
            health.apply(status, failure, fetchedCount, durationMs, finishedAt);
            return health;
        }
    }
}
