package com.jobpilot.sources.cleanup;

import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.DatabaseProof;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TransactionMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Complete immutable preview result. No method on this type can execute its proposals. */
public record SourceLogCleanupPlan(
        Instant previewedAt,
        Duration minimumAge,
        int maxCandidates,
        List<Long> expectedRunningIds,
        Integer expectedRunningCount,
        List<Long> observedRunningIds,
        List<SourceLogCleanupPlanEntry> entries,
        List<String> blockers,
        TransactionMode transactionMode,
        DatabaseProof proofBefore,
        DatabaseProof proofAfter,
        String fingerprint) {

    public SourceLogCleanupPlan {
        expectedRunningIds = List.copyOf(expectedRunningIds);
        observedRunningIds = List.copyOf(observedRunningIds);
        entries = entries.stream()
                .sorted(Comparator.comparingLong(SourceLogCleanupPlanEntry::id)).toList();
        blockers = List.copyOf(blockers);
    }

    public long eligibleCount() {
        return entries.stream().filter(SourceLogCleanupPlanEntry::eligible).count();
    }

    public long rejectedCount() {
        return entries.size() - eligibleCount();
    }

    public long preV10Count() {
        return entries.stream().filter(entry -> entry.ingestionRunId() == null).count();
    }

    public long withTenantChildrenCount() {
        return entries.stream().filter(entry -> entry.tenantSummary().total() > 0).count();
    }

    public long unfinishedTenantAttempts() {
        return entries.stream().mapToLong(entry -> entry.tenantSummary().unfinished()).sum();
    }

    public long laterSuccessEvidenceCount() {
        return entries.stream().mapToLong(entry -> entry.laterEvidence().successCount()).sum();
    }

    public Map<SourceLogCleanupPlanEntry.Confidence, Long> confidenceDistribution() {
        return entries.stream().collect(Collectors.groupingBy(
                SourceLogCleanupPlanEntry::confidence, Collectors.counting()));
    }

    /** A valid read-only observation, including an explicitly expected empty RUNNING set. */
    public boolean previewSafe() {
        return rejectedCount() == 0 && blockers.isEmpty()
                && proofBefore.equals(proofAfter) && transactionMode.safe();
    }

    /** A nonempty plan that is eligible to proceed to the independent WRITE guards. */
    public boolean futureWriteEligible() {
        return !entries.isEmpty() && previewSafe();
    }
}
