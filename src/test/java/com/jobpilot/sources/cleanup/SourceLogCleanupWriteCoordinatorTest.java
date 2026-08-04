package com.jobpilot.sources.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobpilot.sources.cleanup.SourceLogCleanupPlanEntry.Confidence;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.DatabaseProof;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.LaterTerminalEvidence;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TenantSummary;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TransactionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SourceLogCleanupWriteCoordinatorTest {
    private static final List<Long> IDS = List.of(69L, 74L, 79L, 92L, 95L, 96L, 100L);
    private static final String FINGERPRINT = "a".repeat(64);

    private SourceLogCleanupWriteTransaction transaction;
    private SourceLogCleanupWriteCoordinator coordinator;
    private SourceLogCleanupPlan plan;

    @BeforeEach
    void setUp() {
        transaction = mock(SourceLogCleanupWriteTransaction.class);
        coordinator = new SourceLogCleanupWriteCoordinator(transaction);
        plan = plan(IDS, IDS, List.of());
    }

    @Test
    void missingCapabilityFailsBeforeTransaction() {
        assertGuardFailure(properties(false, IDS, "7", FINGERPRINT,
                SourceLogCleanupWriteGuards.CONFIRMATION), plan);
    }

    @Test
    void missingConfirmationFailsBeforeTransaction() {
        assertGuardFailure(properties(true, IDS, "7", FINGERPRINT, null), plan);
    }

    @Test
    void wrongExpectedCountFailsBeforeTransaction() {
        assertGuardFailure(properties(true, IDS.subList(0, 6), "6", FINGERPRINT,
                SourceLogCleanupWriteGuards.CONFIRMATION), plan);
    }

    @Test
    void wrongIdSetFailsBeforeTransaction() {
        assertGuardFailure(properties(true, List.of(69L, 74L, 79L, 92L, 95L, 96L, 101L),
                "7", FINGERPRINT, SourceLogCleanupWriteGuards.CONFIRMATION), plan);
    }

    @Test
    void wrongFingerprintFailsBeforeTransaction() {
        assertGuardFailure(properties(true, IDS, "7", "b".repeat(64),
                SourceLogCleanupWriteGuards.CONFIRMATION), plan);
    }

    @Test
    void unknownAdditionalRunningRowFailsBeforeTransaction() {
        List<Long> observed = new ArrayList<>(IDS);
        observed.add(101L);
        SourceLogCleanupPlan blocked = plan(IDS, observed,
                List.of("COMPLETE_RUNNING_SET_MISMATCH"));

        assertGuardFailure(properties(true, IDS, "7", FINGERPRINT,
                SourceLogCleanupWriteGuards.CONFIRMATION), blocked);
    }

    @Test
    void matchingIndependentGuardsInvokeTransactionExactlyOnce() {
        SourceLogCleanupWriteResult success = SourceLogCleanupWriteResult.success(
                7, IDS, Instant.EPOCH);
        when(transaction.apply(plan)).thenReturn(success);

        SourceLogCleanupWriteResult result = coordinator.execute(plan, properties(
                true, IDS, "7", FINGERPRINT, SourceLogCleanupWriteGuards.CONFIRMATION));

        assertThat(result).isEqualTo(success);
    }

    @Test
    void oldGuardCannotBeAppliedToAnEmptyPostWritePlan() {
        SourceLogCleanupPlan empty = plan(List.of(), List.of(), List.of());

        assertGuardFailure(properties(true, IDS, "7", FINGERPRINT,
                SourceLogCleanupWriteGuards.CONFIRMATION), empty);
    }

    private void assertGuardFailure(SourceLogCleanupProperties properties,
                                    SourceLogCleanupPlan candidatePlan) {
        SourceLogCleanupWriteResult result = coordinator.execute(candidatePlan, properties);
        assertThat(result.status()).isEqualTo(SourceLogCleanupWriteResult.Status.ERROR);
        verifyNoInteractions(transaction);
    }

    private SourceLogCleanupProperties properties(boolean enabled, List<Long> ids, String count,
                                                   String fingerprint, String confirmation) {
        return new SourceLogCleanupProperties(SourceLogCleanupProperties.Mode.WRITE, enabled,
                Duration.ofHours(6), 20, ids.stream().map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse(""), count,
                fingerprint, confirmation);
    }

    private SourceLogCleanupPlan plan(List<Long> expected, List<Long> observed,
                                      List<String> blockers) {
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        List<SourceLogCleanupPlanEntry> entries = expected.stream().map(id ->
                new SourceLogCleanupPlanEntry(id, null, "source-" + id, old, null,
                        Duration.ofDays(1), "RUNNING", id.intValue(), 1, null,
                        new TenantSummary(0, 0, Map.of()),
                        new LaterTerminalEvidence(2, 2, id + 1, "SUCCESS",
                                old.plusSeconds(1)), false, blockers.isEmpty(),
                        Confidence.MODERATE, blockers.isEmpty() ? "ELIGIBLE" : "REJECTED",
                        SourceLogCleanupPreviewService.PROPOSED_STATUS,
                        SourceLogCleanupPreviewService.PROPOSED_CATEGORY,
                        SourceLogCleanupPreviewService.PROPOSED_FINISHED_AT_POLICY,
                        SourceLogCleanupPreviewService.PROPOSED_ERROR_SUMMARY)).toList();
        DatabaseProof proof = new DatabaseProof(List.of());
        return new SourceLogCleanupPlan(Instant.EPOCH, Duration.ofHours(6), 20,
                expected, expected.isEmpty() ? 0 : expected.size(), observed, entries, blockers,
                new TransactionMode("repeatable read", "on"), proof, proof, FINGERPRINT);
    }
}
