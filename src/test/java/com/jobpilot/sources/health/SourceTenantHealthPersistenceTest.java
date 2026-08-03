package com.jobpilot.sources.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Persistence-level behaviour of the recorder against the real V10 schema (H2 in
 * PostgreSQL mode). Deliberately not {@code @Transactional}: the recorder uses
 * REQUIRES_NEW, so the rows must be visible after it returns.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jobpilot-tenant-health;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class SourceTenantHealthPersistenceTest {
    private static final Instant T0 = Instant.parse("2026-08-03T10:00:00Z");

    @Autowired private SourceTenantHealthRecorder recorder;
    @Autowired private SourceTenantHealthRepository health;
    @Autowired private SourceTenantFetchLogRepository attempts;

    @Test
    void oneAttemptWritesExactlyOneHistoryRowAndUpsertsTheRollUp() {
        String tenant = unique("history");
        UUID run = UUID.randomUUID();

        recorder.record(run, "greenhouse", tenant, TenantAttemptStatus.SUCCESS,
                TenantFailure.none(), 4, 120L, T0, T0.plusMillis(120));
        recorder.record(run, "greenhouse", tenant, TenantAttemptStatus.EMPTY_SUCCESS,
                TenantFailure.none(), 0, 80L, T0.plusSeconds(1), T0.plusSeconds(1).plusMillis(80));

        List<SourceTenantFetchLog> rows =
                attempts.findByProviderAndTenantOrderByIdAsc("greenhouse", tenant);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getIngestionRunId()).isEqualTo(run);
            assertThat(row.getFailureCategory()).isEqualTo(TenantFailureCategory.NONE);
            assertThat(row.getErrorMessage()).isNull();
            assertThat(row.getStartedAt()).isNotNull();
            assertThat(row.getFinishedAt()).isNotNull();
        });
        assertThat(rows.get(0).getFetchedCount()).isEqualTo(4);
        assertThat(rows.get(0).getDurationMs()).isEqualTo(120L);

        // One roll-up row per provider+tenant regardless of attempt count.
        assertThat(health.findByProviderAndTenant("greenhouse", tenant)).hasValueSatisfying(row -> {
            assertThat(row.getTotalAttempts()).isEqualTo(2);
            assertThat(row.getTotalSuccesses()).isEqualTo(2);
            assertThat(row.healthy()).isTrue();
        });
    }

    @Test
    void failureDetailIsPersistedThenClearedByARecovery() {
        String tenant = unique("recovery");
        UUID run = UUID.randomUUID();
        TenantFailure notFound = new TenantFailure(TenantFailureCategory.INVALID_TENANT, 404,
                "com.jobpilot.common.ExternalHttpException", "HTTP 404 for ashby tenant " + tenant);

        recorder.record(run, "ashby", tenant, TenantAttemptStatus.FAILURE, notFound,
                0, 30L, T0, T0.plusMillis(30));

        assertThat(health.findByProviderAndTenant("ashby", tenant)).hasValueSatisfying(row -> {
            assertThat(row.getLastFailureCategory()).isEqualTo(TenantFailureCategory.INVALID_TENANT);
            assertThat(row.getLastHttpStatus()).isEqualTo(404);
            assertThat(row.getLastErrorMessage()).contains("HTTP 404");
            assertThat(row.getConsecutiveFailures()).isEqualTo(1);
            assertThat(row.getLastFailureAt()).isNotNull();
            assertThat(row.getLastSuccessAt()).isNull();
        });

        recorder.record(run, "ashby", tenant, TenantAttemptStatus.SUCCESS, TenantFailure.none(),
                9, 55L, T0.plusSeconds(5), T0.plusSeconds(5).plusMillis(55));

        assertThat(health.findByProviderAndTenant("ashby", tenant)).hasValueSatisfying(row -> {
            assertThat(row.getLastFailureCategory()).isEqualTo(TenantFailureCategory.NONE);
            assertThat(row.getLastHttpStatus()).isNull();
            assertThat(row.getLastErrorType()).isNull();
            assertThat(row.getLastErrorMessage()).isNull();
            assertThat(row.getConsecutiveFailures()).isZero();
            assertThat(row.getLastSuccessAt()).isNotNull();
            assertThat(row.getLastFailureAt()).isNotNull();
            assertThat(row.getTotalAttempts()).isEqualTo(2);
        });
        // Immutable history keeps the failure even though the roll-up recovered.
        assertThat(attempts.findByProviderAndTenantOrderByIdAsc("ashby", tenant))
                .extracting(SourceTenantFetchLog::getAttemptStatus)
                .containsExactly(TenantAttemptStatus.FAILURE, TenantAttemptStatus.SUCCESS);
    }

    @Test
    void identicalExternalIdsInDifferentTenantsKeepSeparateHealthRows() {
        String first = unique("tenant-a");
        String second = unique("tenant-b");
        UUID run = UUID.randomUUID();

        recorder.record(run, "recruitee", first, TenantAttemptStatus.SUCCESS,
                TenantFailure.none(), 1, 10L, T0, T0.plusMillis(10));
        recorder.record(run, "recruitee", second, TenantAttemptStatus.FAILURE,
                new TenantFailure(TenantFailureCategory.SERVER_ERROR, 503, "T", "HTTP 503"),
                0, 10L, T0, T0.plusMillis(10));

        assertThat(health.findByProviderAndTenant("recruitee", first))
                .hasValueSatisfying(row -> assertThat(row.healthy()).isTrue());
        assertThat(health.findByProviderAndTenant("recruitee", second))
                .hasValueSatisfying(row -> assertThat(row.healthy()).isFalse());
    }

    @Test
    void attemptsAreCorrelatedByIngestionRunId() {
        UUID run = UUID.randomUUID();
        String tenantA = unique("run-a");
        String tenantB = unique("run-b");

        recorder.record(run, "lever", tenantA, TenantAttemptStatus.SUCCESS, TenantFailure.none(),
                2, 10L, T0, T0.plusMillis(10));
        recorder.record(run, "lever", tenantB, TenantAttemptStatus.SUCCESS, TenantFailure.none(),
                3, 10L, T0, T0.plusMillis(10));
        recorder.record(UUID.randomUUID(), "lever", tenantA, TenantAttemptStatus.SUCCESS,
                TenantFailure.none(), 1, 10L, T0, T0.plusMillis(10));

        assertThat(attempts.findByIngestionRunIdOrderByIdAsc(run)).hasSize(2);
    }

    @Test
    void anOversizedResponseIsRecordedOnceAsResponseTooLargeAndClearedByRecovery() {
        String tenant = unique("oversize");
        UUID run = UUID.randomUUID();
        TenantFailure oversize = new TenantFailure(TenantFailureCategory.RESPONSE_TOO_LARGE, null,
                "com.jobpilot.common.ExternalHttpException",
                "Response exceeded the configured 10485760-byte limit for greenhouse tenant "
                        + tenant);

        recorder.record(run, "greenhouse", tenant, TenantAttemptStatus.FAILURE, oversize,
                0, 2073L, T0, T0.plusMillis(2073));
        recorder.record(run, "greenhouse", tenant, TenantAttemptStatus.FAILURE, oversize,
                0, 1900L, T0.plusSeconds(1), T0.plusSeconds(1).plusMillis(1900));

        assertThat(attempts.findByProviderAndTenantOrderByIdAsc("greenhouse", tenant)).hasSize(2);
        assertThat(health.findByProviderAndTenant("greenhouse", tenant)).hasValueSatisfying(row -> {
            assertThat(row.getLastFailureCategory())
                    .isEqualTo(TenantFailureCategory.RESPONSE_TOO_LARGE);
            // No structural HTTP status exists for a size breach.
            assertThat(row.getLastHttpStatus()).isNull();
            assertThat(row.getConsecutiveFailures()).isEqualTo(2);
            assertThat(row.getLastDurationMs()).isEqualTo(1900L);
            assertThat(row.healthy()).isFalse();
            // Only the closed sentence: no body fragment, URL, or stack frame.
            assertThat(row.getLastErrorMessage()).isEqualTo(
                    "Response exceeded the configured 10485760-byte limit for greenhouse tenant "
                            + tenant);
            assertThat(row.getLastErrorMessage()).doesNotContain("http", "://", "?", "<");
        });

        // A later success under the raised limit resets and clears the failure detail.
        recorder.record(run, "greenhouse", tenant, TenantAttemptStatus.SUCCESS, TenantFailure.none(),
                412, 3100L, T0.plusSeconds(2), T0.plusSeconds(2).plusMillis(3100));

        assertThat(health.findByProviderAndTenant("greenhouse", tenant)).hasValueSatisfying(row -> {
            assertThat(row.healthy()).isTrue();
            assertThat(row.getLastFailureCategory()).isEqualTo(TenantFailureCategory.NONE);
            assertThat(row.getLastErrorType()).isNull();
            assertThat(row.getLastErrorMessage()).isNull();
            assertThat(row.getConsecutiveFailures()).isZero();
            assertThat(row.getLastFetchedCount()).isEqualTo(412);
            assertThat(row.getTotalAttempts()).isEqualTo(3);
        });
        // The historical oversize rows stay immutable.
        assertThat(attempts.findByProviderAndTenantOrderByIdAsc("greenhouse", tenant))
                .extracting(SourceTenantFetchLog::getFailureCategory)
                .containsExactly(TenantFailureCategory.RESPONSE_TOO_LARGE,
                        TenantFailureCategory.RESPONSE_TOO_LARGE, TenantFailureCategory.NONE);
    }

    /** Keeps rows independent without a transactional rollback, which REQUIRES_NEW ignores. */
    private String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
