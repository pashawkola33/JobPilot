package com.jobpilot.matching.rescore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCard;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScoreRescoreWriteCoordinatorTest {
    private ScoreRescoreWriteTransaction transaction;
    private ScoreRescoreWriteCoordinator coordinator;
    private ScoreRescorePlan plan;

    @BeforeEach
    void setUp() {
        transaction = mock(ScoreRescoreWriteTransaction.class);
        coordinator = new ScoreRescoreWriteCoordinator(transaction);
        plan = plan();
    }

    @Test
    void disabledWriteCapabilityFailsBeforeTransaction() {
        assertError(properties(false, "1", plan.fingerprint(), "250",
                ScoreRescoreCommandGuards.CONFIRMATION));
    }

    @Test
    void missingExpectedCountFailsBeforeTransaction() {
        assertError(properties(true, null, plan.fingerprint(), "250",
                ScoreRescoreCommandGuards.CONFIRMATION));
    }

    @Test
    void missingFingerprintFailsBeforeTransaction() {
        assertError(properties(true, "1", null, "250",
                ScoreRescoreCommandGuards.CONFIRMATION));
    }

    @Test
    void wrongCountFailsBeforeTransaction() {
        assertError(properties(true, "2", plan.fingerprint(), "250",
                ScoreRescoreCommandGuards.CONFIRMATION));
    }

    @Test
    void wrongFingerprintFailsBeforeTransaction() {
        assertError(properties(true, "1", "0".repeat(64), "250",
                ScoreRescoreCommandGuards.CONFIRMATION));
    }

    @Test
    void missingMaximumFailsBeforeTransaction() {
        assertError(properties(true, "1", plan.fingerprint(), null,
                ScoreRescoreCommandGuards.CONFIRMATION));
    }

    @Test
    void wrongConfirmationFailsBeforeTransaction() {
        assertError(properties(true, "1", plan.fingerprint(), "250", "yes"));
    }

    @Test
    void everyMatchingGuardInvokesTransactionExactlyOnce() {
        ScoreRescoreWriteResult success = ScoreRescoreWriteResult.success(
                1, 1, List.of(5L), Instant.EPOCH);
        when(transaction.apply(plan)).thenReturn(success);

        ScoreRescoreWriteResult result = coordinator.execute(plan, properties(true, "1",
                plan.fingerprint(), "250", ScoreRescoreCommandGuards.CONFIRMATION));

        assertThat(result).isEqualTo(success);
    }

    @Test
    void oldFingerprintCannotBeAppliedToThePostWriteEmptyPlan() {
        ScoreRescorePlan empty = new ScoreRescorePlan(report(1, 0), List.of());

        ScoreRescoreWriteResult result = coordinator.execute(empty, properties(true, "1",
                plan.fingerprint(), "250", ScoreRescoreCommandGuards.CONFIRMATION));

        assertThat(result.status()).isEqualTo(ScoreRescoreWriteResult.Status.ERROR);
        verifyNoInteractions(transaction);
    }

    private void assertError(ScoreRescoreCommandProperties properties) {
        ScoreRescoreWriteResult result = coordinator.execute(plan, properties);
        assertThat(result.status()).isEqualTo(ScoreRescoreWriteResult.Status.ERROR);
        verifyNoInteractions(transaction);
    }

    private ScoreRescoreCommandProperties properties(boolean enabled, String count,
                                                       String fingerprint, String maximum,
                                                       String confirmation) {
        return new ScoreRescoreCommandProperties(ScoreRescoreCommandProperties.Mode.WRITE,
                enabled, count, fingerprint, maximum, confirmation);
    }

    private ScoreRescorePlan plan() {
        ExtractedRequirements oldRequirements = requirements("MIDDLE");
        ExtractedRequirements freshRequirements = requirements("JUNIOR");
        ScoreCard oldScore = score(0, ScoreBand.UNSUITABLE,
                List.of("Middle or senior seniority"));
        ScoreCard freshScore = score(56, ScoreBand.POSSIBLE_MATCH, List.of());
        ScoreRescorePlanEntry entry = new ScoreRescorePlanEntry(5, 10, 20,
                ScreeningDisposition.REVIEW, "description", "content", Instant.EPOCH,
                "json", oldScore, oldRequirements, freshScore, freshRequirements);
        return new ScoreRescorePlan(report(1), List.of(entry));
    }

    private ScoreRescorePreviewReport report(int inspected) {
        return report(inspected, 1);
    }

    /** The plan now asserts its entry count against the report, so fixtures must agree. */
    private ScoreRescorePreviewReport report(int inspected, int changedPlan) {
        var queue = new ScoreRescorePreviewReport.QueueProjection(List.of(), List.of());
        return new ScoreRescorePreviewReport(inspected, inspected - 1, 1, 1, 1, 0,
                1, 0, Map.of(56, 1), 1, 0,
                new ScoreRescorePreviewReport.BoundaryCrossings(List.of(5L), List.of(5L),
                        List.of()), List.of(), queue, queue, List.of(), null,
                new ScoreRescorePreviewReport.ChangeCounts(changedPlan, changedPlan, 0,
                        changedPlan, inspected - changedPlan),
                List.of());
    }

    private ExtractedRequirements requirements(String seniority) {
        return new ExtractedRequirements(seniority, false, null, null, false,
                List.of("Java"), List.of("Java"), List.of(), "Bucharest",
                "Romania eligible", List.of(), null, null, null, "DETERMINISTIC");
    }

    private ScoreCard score(int value, ScoreBand band, List<String> blockers) {
        return new ScoreCard(value, band, blockers.isEmpty(), 25, 9, 3, 0, 8, 10, 1,
                0, List.of("strength"), List.of(), blockers);
    }
}
