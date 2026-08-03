package com.jobpilot.sources.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SourceTenantHealthTest {
    private static final Instant T0 = Instant.parse("2026-08-03T09:00:00Z");

    @Test
    void firstSuccessInitialisesEveryCounterAndTimestamp() {
        SourceTenantHealth health = new SourceTenantHealth("ashby", "linear", T0);

        health.apply(TenantAttemptStatus.SUCCESS, TenantFailure.none(), 12, 340L, T0);

        assertThat(health.healthy()).isTrue();
        assertThat(health.degraded()).isFalse();
        assertThat(health.getLastAttemptStatus()).isEqualTo(TenantAttemptStatus.SUCCESS);
        assertThat(health.getLastFailureCategory()).isEqualTo(TenantFailureCategory.NONE);
        assertThat(health.getLastFetchedCount()).isEqualTo(12);
        assertThat(health.getLastDurationMs()).isEqualTo(340L);
        assertThat(health.getConsecutiveFailures()).isZero();
        assertThat(health.getTotalAttempts()).isEqualTo(1);
        assertThat(health.getTotalSuccesses()).isEqualTo(1);
        assertThat(health.getTotalFailures()).isZero();
        assertThat(health.getLastAttemptAt()).isEqualTo(T0);
        assertThat(health.getLastSuccessAt()).isEqualTo(T0);
        assertThat(health.getLastFailureAt()).isNull();
        assertThat(health.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void emptySuccessCountsAsReachableAndSuccessful() {
        SourceTenantHealth health = new SourceTenantHealth("lever", "collabora", T0);

        health.apply(TenantAttemptStatus.EMPTY_SUCCESS, TenantFailure.none(), 0, 90L, T0);

        assertThat(health.healthy()).isTrue();
        assertThat(health.getLastFetchedCount()).isZero();
        assertThat(health.getConsecutiveFailures()).isZero();
        assertThat(health.getTotalSuccesses()).isEqualTo(1);
        assertThat(health.getTotalFailures()).isZero();
        assertThat(health.getLastSuccessAt()).isEqualTo(T0);
    }

    @Test
    void repeatedFailuresKeepIncrementingAndBecomeDegraded() {
        SourceTenantHealth health = new SourceTenantHealth("ashby", "cohere", T0);
        TenantFailure notFound = new TenantFailure(TenantFailureCategory.INVALID_TENANT, 404,
                "com.jobpilot.common.ExternalHttpException", "HTTP 404 for ashby tenant cohere");

        for (int attempt = 1; attempt <= 3; attempt++) {
            health.apply(TenantAttemptStatus.FAILURE, notFound, 0, 20L, T0.plusSeconds(attempt));
            assertThat(health.getConsecutiveFailures()).isEqualTo(attempt);
        }

        assertThat(health.healthy()).isFalse();
        assertThat(health.degraded()).isTrue();
        assertThat(health.getLastFailureCategory()).isEqualTo(TenantFailureCategory.INVALID_TENANT);
        assertThat(health.getLastHttpStatus()).isEqualTo(404);
        assertThat(health.getLastErrorMessage()).isEqualTo("HTTP 404 for ashby tenant cohere");
        assertThat(health.getTotalAttempts()).isEqualTo(3);
        assertThat(health.getTotalFailures()).isEqualTo(3);
        assertThat(health.getTotalSuccesses()).isZero();
        assertThat(health.getLastSuccessAt()).isNull();
    }

    @Test
    void failurePreservesTheEarlierSuccessTimestamp() {
        SourceTenantHealth health = new SourceTenantHealth("greenhouse", "elastic", T0);
        health.apply(TenantAttemptStatus.SUCCESS, TenantFailure.none(), 5, 100L, T0);

        Instant later = T0.plus(Duration.ofHours(6));
        health.apply(TenantAttemptStatus.FAILURE, serverError(), 0, 50L, later);

        assertThat(health.getLastSuccessAt()).isEqualTo(T0);
        assertThat(health.getLastFailureAt()).isEqualTo(later);
        assertThat(health.getLastAttemptAt()).isEqualTo(later);
    }

    @Test
    void successResetsConsecutiveFailuresAndClearsLastFailureDetail() {
        SourceTenantHealth health = new SourceTenantHealth("greenhouse", "twilio", T0);
        health.apply(TenantAttemptStatus.FAILURE, serverError(), 0, 40L, T0);
        health.apply(TenantAttemptStatus.FAILURE, serverError(), 0, 40L, T0.plusSeconds(1));

        Instant recovery = T0.plusSeconds(2);
        health.apply(TenantAttemptStatus.SUCCESS, TenantFailure.none(), 7, 210L, recovery);

        assertThat(health.getConsecutiveFailures()).isZero();
        assertThat(health.degraded()).isFalse();
        assertThat(health.getLastFailureCategory()).isEqualTo(TenantFailureCategory.NONE);
        assertThat(health.getLastHttpStatus()).isNull();
        assertThat(health.getLastErrorType()).isNull();
        assertThat(health.getLastErrorMessage()).isNull();
        // Failure history is retained even though the current failure detail was cleared.
        assertThat(health.getLastFailureAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(health.getTotalFailures()).isEqualTo(2);
    }

    @Test
    void totalsStayMathematicallyConsistentAcrossMixedOutcomes() {
        SourceTenantHealth health = new SourceTenantHealth("recruitee", "pleo", T0);

        health.apply(TenantAttemptStatus.SUCCESS, TenantFailure.none(), 3, 10L, T0);
        health.apply(TenantAttemptStatus.FAILURE, serverError(), 0, 10L, T0.plusSeconds(1));
        health.apply(TenantAttemptStatus.EMPTY_SUCCESS, TenantFailure.none(), 0, 10L, T0.plusSeconds(2));
        health.apply(TenantAttemptStatus.FAILURE, serverError(), 0, 10L, T0.plusSeconds(3));
        health.apply(TenantAttemptStatus.FAILURE, serverError(), 0, 10L, T0.plusSeconds(4));

        assertThat(health.getTotalAttempts()).isEqualTo(5);
        assertThat(health.getTotalSuccesses()).isEqualTo(2);
        assertThat(health.getTotalFailures()).isEqualTo(3);
        assertThat(health.getTotalAttempts())
                .isEqualTo(health.getTotalSuccesses() + health.getTotalFailures());
        assertThat(health.getConsecutiveFailures()).isEqualTo(2);
    }

    @Test
    void aFailureWithoutACategoryIsStoredAsUnknownRatherThanNone() {
        SourceTenantHealth health = new SourceTenantHealth("lever", "veeva", T0);

        health.apply(TenantAttemptStatus.FAILURE, TenantFailure.none(), 0, 5L, T0);

        assertThat(health.getLastFailureCategory()).isEqualTo(TenantFailureCategory.UNKNOWN_ERROR);
    }

    @Test
    void negativeCountsAndDurationsAreClampedToZero() {
        SourceTenantHealth health = new SourceTenantHealth("ashby", "notion", T0);

        health.apply(TenantAttemptStatus.SUCCESS, TenantFailure.none(), -4, -9L, T0);

        assertThat(health.getLastFetchedCount()).isZero();
        assertThat(health.getLastDurationMs()).isZero();
    }

    private TenantFailure serverError() {
        return new TenantFailure(TenantFailureCategory.SERVER_ERROR, 503,
                "com.jobpilot.common.ExternalHttpException", "HTTP 503 for provider tenant");
    }
}
