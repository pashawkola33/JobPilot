package com.jobpilot.sources.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobpilot.sources.SourceFetchExecutionRegistry;
import com.jobpilot.sources.SourceFetchLogHandle;
import com.jobpilot.sources.cleanup.SourceLogCleanupPlanEntry.Confidence;
import com.jobpilot.sources.cleanup.SourceLogCleanupProperties.Guards;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.DatabaseProof;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.LaterTerminalEvidence;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.SourceRow;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TableProof;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TenantSummary;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TransactionMode;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

class SourceLogCleanupPreviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");
    private static final Instant JVM_START = Instant.parse("2026-08-05T08:00:00Z");
    private static final Instant OLD = Instant.parse("2026-08-02T09:00:00Z");
    private static final DatabaseProof PROOF = new DatabaseProof(List.of(
            new TableProof("source_fetch_logs", 9, "a".repeat(64), OLD)));

    private final SourceLogCleanupReadRepository repository =
            mock(SourceLogCleanupReadRepository.class);
    private final SourceFetchExecutionRegistry executions = new SourceFetchExecutionRegistry();

    @Test
    void exactHistoricalSevenRowSetProducesDeterministicEligiblePlan() {
        List<SourceRow> rows = historicalSeven();
        stubSnapshot(rows);
        for (SourceRow row : rows) {
            if (row.ingestionRunId() != null) {
                when(repository.tenantSummary(row.ingestionRunId())).thenReturn(terminalTenant());
            }
            when(repository.laterTerminalEvidence(row)).thenReturn(row.ingestionRunId() == null
                    ? later(2, 2, row.id() + 20)
                    : later(1, 1, row.id() + 20));
        }
        SourceLogCleanupPreviewService service = service(JVM_START);
        Guards guards = guards(rows, 20);

        SourceLogCleanupPlan first = service.plan(guards);
        SourceLogCleanupPlan second = service.plan(guards);

        assertThat(first.observedRunningIds())
                .containsExactly(69L, 74L, 79L, 92L, 95L, 96L, 100L);
        assertThat(first.eligibleCount()).isEqualTo(7);
        assertThat(first.rejectedCount()).isZero();
        assertThat(first.preV10Count()).isEqualTo(3);
        assertThat(first.futureWriteEligible()).isTrue();
        assertThat(first.entries().subList(0, 3))
                .allMatch(entry -> entry.confidence() == Confidence.MODERATE);
        assertThat(first.entries().subList(3, 7))
                .allMatch(entry -> entry.confidence() == Confidence.HIGH);
        assertThat(first.fingerprint()).hasSize(64).isEqualTo(second.fingerprint());
    }

    @Test
    void explicitlyExpectedEmptySetIsASafeReadOnlyPreviewButNeverWriteEligible() {
        stubSnapshot(List.of());

        SourceLogCleanupPlan plan = service(JVM_START).plan(
                new Guards(Duration.ofHours(6), 20, List.of(), 0));

        assertThat(plan.observedRunningIds()).isEmpty();
        assertThat(plan.entries()).isEmpty();
        assertThat(plan.previewSafe()).isTrue();
        assertThat(plan.futureWriteEligible()).isFalse();
        assertThat(plan.blockers()).isEmpty();
    }

    @Test
    void unknownAdditionalRunningRowFailsClosed() {
        SourceRow known = row(69, null, "greenhouse", OLD);
        SourceRow unknown = row(70, null, "lever", OLD);
        stubSnapshot(List.of(known, unknown));
        stubLater(known, later(2, 2, 90));
        stubLater(unknown, later(2, 2, 91));

        SourceLogCleanupPlan plan = service(JVM_START).plan(
                new Guards(Duration.ofHours(6), 20, List.of(69L), 1));

        assertThat(plan.blockers()).contains("COMPLETE_RUNNING_SET_MISMATCH");
        assertThat(plan.futureWriteEligible()).isFalse();
    }

    @Test
    void missingExpectedRunningRowFailsClosed() {
        SourceRow row = row(69, null, "greenhouse", OLD);
        stubSnapshot(List.of(row));
        stubLater(row, later(2, 2, 90));

        SourceLogCleanupPlan plan = service(JVM_START).plan(
                new Guards(Duration.ofHours(6), 20, List.of(69L, 74L), null));

        assertThat(plan.blockers()).contains("COMPLETE_RUNNING_SET_MISMATCH");
    }

    @Test
    void youngRunningRowIsRejected() {
        SourceRow row = row(69, null, "greenhouse", NOW.minus(Duration.ofHours(1)));
        stubSnapshot(List.of(row));
        stubLater(row, later(2, 2, 90));

        SourceLogCleanupPlan plan = service(NOW.minus(Duration.ofHours(2))).plan(guards(row));

        assertThat(plan.entries().getFirst().eligible()).isFalse();
        assertThat(plan.entries().getFirst().reason()).contains("TOO_YOUNG");
    }

    @Test
    void unfinishedTenantAttemptIsRejected() {
        UUID run = UUID.randomUUID();
        SourceRow row = row(92, run, "workday", OLD);
        stubSnapshot(List.of(row));
        when(repository.tenantSummary(run)).thenReturn(
                new TenantSummary(1, 1, Map.of("SUCCESS", 1L)));
        stubLater(row, later(1, 1, 110));

        SourceLogCleanupPlan plan = service(JVM_START).plan(guards(row));

        assertThat(plan.entries().getFirst().eligible()).isFalse();
        assertThat(plan.entries().getFirst().reason()).contains("UNFINISHED_TENANT_ATTEMPTS");
    }

    @Test
    void terminalTenantChildrenAreAccepted() {
        UUID run = UUID.randomUUID();
        SourceRow row = row(92, run, "workday", OLD);
        stubSnapshot(List.of(row));
        when(repository.tenantSummary(run)).thenReturn(terminalTenant());
        stubLater(row, later(1, 0, 110));

        SourceLogCleanupPlan plan = service(JVM_START).plan(guards(row));

        assertThat(plan.entries().getFirst().eligible()).isTrue();
        assertThat(plan.entries().getFirst().confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void preV10NullRunRequiresMultipleLaterSuccesses() {
        SourceRow row = row(69, null, "greenhouse", OLD);
        stubSnapshot(List.of(row));
        stubLater(row, later(2, 2, 90));

        SourceLogCleanupPlan plan = service(JVM_START).plan(guards(row));

        assertThat(plan.entries().getFirst().eligible()).isTrue();
        assertThat(plan.entries().getFirst().confidence()).isEqualTo(Confidence.MODERATE);
    }

    @Test
    void preV10WithoutLaterSuccessEvidenceIsRejected() {
        SourceRow row = row(69, null, "greenhouse", OLD);
        stubSnapshot(List.of(row));
        stubLater(row, later(2, 1, 90));

        SourceLogCleanupPlan plan = service(JVM_START).plan(guards(row));

        assertThat(plan.entries().getFirst().reason())
                .contains("INSUFFICIENT_PRE_V10_LATER_SUCCESS_EVIDENCE");
        assertThat(plan.futureWriteEligible()).isFalse();
    }

    @Test
    void rowFromCurrentJvmPeriodIsRejected() {
        SourceRow row = row(69, null, "greenhouse", NOW.minus(Duration.ofHours(10)));
        stubSnapshot(List.of(row));
        stubLater(row, later(2, 2, 90));

        SourceLogCleanupPlan plan = service(NOW.minus(Duration.ofHours(12))).plan(guards(row));

        assertThat(plan.entries().getFirst().reason()).contains("CURRENT_JVM_PERIOD");
    }

    @Test
    void candidateCapIsEnforced() {
        SourceRow first = row(69, null, "greenhouse", OLD);
        SourceRow second = row(74, null, "lever", OLD);
        stubSnapshot(List.of(first, second));
        stubLater(first, later(2, 2, 90));
        stubLater(second, later(2, 2, 91));

        SourceLogCleanupPlan plan = service(JVM_START).plan(new Guards(
                Duration.ofHours(6), 1, List.of(69L, 74L), 2));

        assertThat(plan.blockers()).contains("CANDIDATE_LIMIT_EXCEEDED");
        assertThat(plan.futureWriteEligible()).isFalse();
    }

    @Test
    void changedRunningRowDuringPlanningFailsClosed() {
        SourceRow before = row(69, null, "greenhouse", OLD);
        SourceRow after = new SourceRow(69, null, "greenhouse", OLD, null,
                "RUNNING", 1, 0, null);
        stubCommon();
        when(repository.runningRows()).thenReturn(List.of(before), List.of(after));
        stubLater(before, later(2, 2, 90));

        SourceLogCleanupPlan plan = service(JVM_START).plan(guards(before));

        assertThat(plan.blockers()).contains("RUNNING_SET_CHANGED_DURING_PREVIEW");
    }

    @Test
    void activeSameSourceExecutionFailsClosed() {
        SourceRow row = row(69, null, "greenhouse", OLD);
        stubSnapshot(List.of(row));
        stubLater(row, later(2, 2, 90));
        executions.register(new SourceFetchLogHandle(999, "greenhouse", UUID.randomUUID()));

        SourceLogCleanupPlan plan = service(JVM_START).plan(guards(row));

        assertThat(plan.blockers()).contains("LIVE_OWNER_PRESENT");
        assertThat(plan.entries().getFirst().reason()).contains("LIVE_OWNER_EVIDENCE");
    }

    @Test
    void transactionContractAndQueryBoundaryExposeNoWriteMethod() throws Exception {
        Method planMethod = SourceLogCleanupPreviewService.class.getMethod(
                "plan", SourceLogCleanupProperties.Guards.class);
        Transactional transaction = planMethod.getAnnotation(Transactional.class);

        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
        assertThat(SourceLogCleanupReadRepository.class.getDeclaredMethods())
                .noneMatch(method -> method.getName().matches("(?i).*(save|update|delete|write).*"));
    }

    private SourceLogCleanupPreviewService service(Instant jvmStartedAt) {
        return new SourceLogCleanupPreviewService(repository, executions,
                new JvmStartTime(jvmStartedAt), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubSnapshot(List<SourceRow> rows) {
        stubCommon();
        when(repository.runningRows()).thenReturn(List.copyOf(rows));
    }

    private void stubCommon() {
        when(repository.transactionMode()).thenReturn(new TransactionMode("repeatable read", "on"));
        when(repository.databaseProof()).thenReturn(PROOF);
    }

    private void stubLater(SourceRow row, LaterTerminalEvidence evidence) {
        when(repository.laterTerminalEvidence(row)).thenReturn(evidence);
    }

    private Guards guards(SourceRow row) {
        return new Guards(Duration.ofHours(6), 20, List.of(row.id()), 1);
    }

    private Guards guards(List<SourceRow> rows, int max) {
        return new Guards(Duration.ofHours(6), max, rows.stream().map(SourceRow::id).toList(),
                rows.size());
    }

    private SourceRow row(long id, UUID run, String source, Instant started) {
        return new SourceRow(id, run, source, started, null, "RUNNING", 0, 0, null);
    }

    private TenantSummary terminalTenant() {
        return new TenantSummary(3, 0, Map.of(
                "SUCCESS", 1L, "EMPTY_SUCCESS", 1L, "FAILURE", 1L));
    }

    private LaterTerminalEvidence later(long terminal, long success, long latestId) {
        return new LaterTerminalEvidence(terminal, success, latestId, "SUCCESS",
                NOW.minus(Duration.ofDays(1)));
    }

    private List<SourceRow> historicalSeven() {
        long[] ids = {69, 74, 79, 92, 95, 96, 100};
        ArrayList<SourceRow> rows = new ArrayList<>();
        for (int index = 0; index < ids.length; index++) {
            UUID run = index < 3 ? null : UUID.nameUUIDFromBytes(("run-" + ids[index]).getBytes());
            rows.add(row(ids[index], run, "source" + ids[index], OLD.plusSeconds(index)));
        }
        return List.copyOf(rows);
    }
}
