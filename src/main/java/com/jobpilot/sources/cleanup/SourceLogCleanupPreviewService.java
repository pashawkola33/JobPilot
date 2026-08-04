package com.jobpilot.sources.cleanup;

import com.jobpilot.sources.SourceFetchExecutionRegistry;
import com.jobpilot.sources.SourceFetchLogHandle;
import com.jobpilot.sources.cleanup.SourceLogCleanupPlanEntry.Confidence;
import com.jobpilot.sources.cleanup.SourceLogCleanupProperties.Guards;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.DatabaseProof;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.LaterTerminalEvidence;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.SourceRow;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TenantSummary;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TransactionMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Builds the immutable evidence plan in one read-only snapshot. */
@Service
public class SourceLogCleanupPreviewService {
    public static final String PROPOSED_STATUS = "FAILED";
    public static final String PROPOSED_CATEGORY = "PROCESS_INTERRUPTED";
    public static final String PROPOSED_FINISHED_AT_POLICY = "WRITE_TRANSACTION_TIMESTAMP";
    public static final String PROPOSED_ERROR_SUMMARY =
            "PROCESS_INTERRUPTED: HistoricalOrphanReconciliation";

    private final SourceLogCleanupReadRepository repository;
    private final SourceFetchExecutionRegistry executions;
    private final JvmStartTime jvmStart;
    private final Clock clock;

    @Autowired
    public SourceLogCleanupPreviewService(SourceLogCleanupReadRepository repository,
                                          SourceFetchExecutionRegistry executions,
                                          JvmStartTime jvmStart) {
        this(repository, executions, jvmStart, Clock.systemUTC());
    }

    SourceLogCleanupPreviewService(SourceLogCleanupReadRepository repository,
                                   SourceFetchExecutionRegistry executions,
                                   JvmStartTime jvmStart, Clock clock) {
        this.repository = repository;
        this.executions = executions;
        this.jvmStart = jvmStart;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceLogCleanupPlan plan(Guards guards) {
        Instant previewedAt = clock.instant();
        TransactionMode transactionMode = repository.transactionMode();
        List<SourceFetchLogHandle> activeBefore = executions.snapshot();
        DatabaseProof proofBefore = repository.databaseProof();
        List<SourceRow> first = repository.runningRows();
        if (first.size() > SourceLogCleanupProperties.HARD_MAX_CANDIDATES) {
            throw new IllegalStateException("Hard candidate limit exceeded");
        }
        List<Long> observedIds = first.stream().map(SourceRow::id).toList();

        ArrayList<String> blockers = globalBlockers(
                guards, transactionMode, observedIds, activeBefore);
        ArrayList<SourceLogCleanupPlanEntry> entries = new ArrayList<>();
        for (SourceRow row : first) {
            TenantSummary tenants = row.ingestionRunId() == null
                    ? new TenantSummary(0, 0, java.util.Map.of())
                    : repository.tenantSummary(row.ingestionRunId());
            LaterTerminalEvidence later = repository.laterTerminalEvidence(row);
            entries.add(evaluate(row, first, tenants, later, activeBefore, previewedAt,
                    guards.minimumAge()));
        }
        List<SourceRow> second = repository.runningRows();
        List<SourceFetchLogHandle> activeAfter = executions.snapshot();
        DatabaseProof proofAfter = repository.databaseProof();
        if (!first.equals(second)) blockers.add("RUNNING_SET_CHANGED_DURING_PREVIEW");
        if (!activeBefore.equals(activeAfter)) blockers.add("LIVE_OWNER_SET_CHANGED_DURING_PREVIEW");
        if (!activeAfter.isEmpty() && activeBefore.isEmpty()) blockers.add("LIVE_OWNER_PRESENT");
        if (!proofBefore.equals(proofAfter)) blockers.add("PROTECTED_DATA_CHANGED_DURING_PREVIEW");

        if (!blockers.isEmpty()) {
            entries.replaceAll(this::rejectForGlobalGuard);
        }
        entries.stream().filter(entry -> !entry.eligible()).forEach(entry ->
                blockers.add("CANDIDATE_" + entry.id() + "_" + entry.reason()));
        List<String> stableBlockers = blockers.stream().distinct().sorted().toList();
        String fingerprint = SourceLogCleanupPlanFingerprint.fingerprint(
                guards, observedIds, entries, stableBlockers);
        return new SourceLogCleanupPlan(previewedAt, guards.minimumAge(),
                guards.maxCandidates(), guards.expectedRunningIds(),
                guards.expectedRunningCount(), observedIds, entries, stableBlockers,
                transactionMode, proofBefore, proofAfter, fingerprint);
    }

    private ArrayList<String> globalBlockers(Guards guards, TransactionMode transactionMode,
                                             List<Long> observedIds,
                                             List<SourceFetchLogHandle> active) {
        ArrayList<String> blockers = new ArrayList<>();
        if (!transactionMode.safe()) blockers.add("TRANSACTION_NOT_REPEATABLE_READ_ONLY");
        if (!observedIds.equals(guards.expectedRunningIds())) {
            blockers.add("COMPLETE_RUNNING_SET_MISMATCH");
        }
        if (guards.expectedRunningCount() != null
                && observedIds.size() != guards.expectedRunningCount()) {
            blockers.add("EXPECTED_RUNNING_COUNT_MISMATCH");
        }
        if (observedIds.size() > guards.maxCandidates()) {
            blockers.add("CANDIDATE_LIMIT_EXCEEDED");
        }
        if (observedIds.size() > SourceLogCleanupProperties.HARD_MAX_CANDIDATES) {
            blockers.add("HARD_CANDIDATE_LIMIT_EXCEEDED");
        }
        if (!active.isEmpty()) blockers.add("LIVE_OWNER_PRESENT");
        return blockers;
    }

    private SourceLogCleanupPlanEntry evaluate(
            SourceRow row, List<SourceRow> allRows, TenantSummary tenants,
            LaterTerminalEvidence later, List<SourceFetchLogHandle> active,
            Instant previewedAt, Duration minimumAge) {
        ArrayList<String> rejection = new ArrayList<>();
        boolean preV10 = row.ingestionRunId() == null;
        boolean owner = owned(row, active);
        Duration age = age(row.startedAt(), previewedAt);

        if (row.id() < 1 || row.sourceName() == null || row.sourceName().isBlank()
                || row.startedAt() == null || row.status() == null
                || row.fetchedCount() < 0 || row.savedCount() < 0) {
            rejection.add("MALFORMED_ROW");
        }
        if (!"RUNNING".equals(row.status()) || row.finishedAt() != null) {
            rejection.add("NOT_OPEN_RUNNING_ROW");
        }
        if (row.startedAt() != null) {
            if (row.startedAt().isAfter(previewedAt)
                    || age.compareTo(minimumAge) < 0) rejection.add("TOO_YOUNG");
            if (!row.startedAt().isBefore(jvmStart.value())) rejection.add("CURRENT_JVM_PERIOD");
        }
        if (owner) rejection.add("LIVE_OWNER_EVIDENCE");
        if (!preV10 && allRows.stream().filter(other -> row.ingestionRunId().equals(
                other.ingestionRunId())).count() > 1) {
            rejection.add("SAME_RUN_HAS_ANOTHER_RUNNING_SOURCE");
        }
        if (!consistent(later)) rejection.add("MALFORMED_LATER_RUN_EVIDENCE");

        if (preV10) {
            if (later.successCount() < 2) {
                rejection.add("INSUFFICIENT_PRE_V10_LATER_SUCCESS_EVIDENCE");
            }
        } else {
            if (tenants.total() == 0) rejection.add("NO_TENANT_ATTEMPTS");
            if (tenants.unfinished() > 0) rejection.add("UNFINISHED_TENANT_ATTEMPTS");
            if (!consistent(tenants)) rejection.add("MALFORMED_TENANT_SUMMARY");
            if (later.terminalCount() == 0) rejection.add("NO_LATER_TERMINAL_SOURCE_RUN");
        }

        boolean eligible = rejection.isEmpty();
        Confidence confidence = preV10 ? Confidence.MODERATE : Confidence.HIGH;
        String reason = eligible
                ? preV10
                    ? "PRE_V10_NULL_RUN_WITH_MULTIPLE_LATER_SUCCESSES_AND_NO_LIVE_OWNER"
                    : "TERMINAL_TENANT_CHILDREN_LATER_TERMINAL_RUN_AND_NO_LIVE_OWNER"
                : "REJECTED:" + String.join(",", rejection);
        return new SourceLogCleanupPlanEntry(row.id(), row.ingestionRunId(), row.sourceName(),
                row.startedAt(), row.finishedAt(), age, row.status(), row.fetchedCount(),
                row.savedCount(), row.errorSummary(), tenants, later, owner, eligible, confidence,
                reason, PROPOSED_STATUS, PROPOSED_CATEGORY, PROPOSED_FINISHED_AT_POLICY,
                PROPOSED_ERROR_SUMMARY);
    }

    private boolean owned(SourceRow row, List<SourceFetchLogHandle> active) {
        return active.stream().anyMatch(handle -> handle.id() == row.id()
                || handle.sourceName().equals(row.sourceName())
                || row.ingestionRunId() != null
                    && row.ingestionRunId().equals(handle.ingestionRunId()));
    }

    private SourceLogCleanupPlanEntry rejectForGlobalGuard(SourceLogCleanupPlanEntry entry) {
        String reason = entry.eligible()
                ? "REJECTED:GLOBAL_GUARDS"
                : entry.reason() + ",GLOBAL_GUARDS";
        return new SourceLogCleanupPlanEntry(entry.id(), entry.ingestionRunId(),
                entry.sourceName(), entry.startedAt(), entry.finishedAt(), entry.age(),
                entry.currentStatus(), entry.fetchedCount(), entry.savedCount(),
                entry.existingErrorSummary(), entry.tenantSummary(), entry.laterEvidence(),
                entry.activeOwnerEvidence(), false, entry.confidence(), reason,
                entry.proposedStatus(), entry.proposedFailureCategory(),
                entry.proposedFinishedAtPolicy(), entry.proposedErrorSummary());
    }

    private boolean consistent(TenantSummary tenants) {
        Set<String> terminal = Set.of("SUCCESS", "EMPTY_SUCCESS", "FAILURE");
        if (!terminal.containsAll(tenants.terminalStates().keySet())) return false;
        long summarized = tenants.terminalStates().values().stream()
                .mapToLong(Long::longValue).sum();
        return tenants.total() >= 0 && tenants.unfinished() >= 0
                && tenants.unfinished() <= tenants.total() && summarized == tenants.total();
    }

    private boolean consistent(LaterTerminalEvidence evidence) {
        if (evidence.terminalCount() < 0 || evidence.successCount() < 0
                || evidence.successCount() > evidence.terminalCount()) return false;
        if (evidence.terminalCount() == 0) {
            return evidence.latestId() == null && evidence.latestStatus() == null
                    && evidence.latestFinishedAt() == null;
        }
        return evidence.latestId() != null && evidence.latestId() > 0
                && Set.of("SUCCESS", "FAILED").contains(evidence.latestStatus())
                && evidence.latestFinishedAt() != null;
    }

    private Duration age(Instant startedAt, Instant previewedAt) {
        if (startedAt == null || startedAt.isAfter(previewedAt)) return Duration.ZERO;
        return Duration.between(startedAt, previewedAt);
    }
}
