package com.jobpilot.sources.cleanup;

import com.jobpilot.common.Utf16;
import com.jobpilot.common.Hashing;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TableProof;
import com.jobpilot.sources.health.SafeErrorText;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Bounded operator output containing no raw error, URL, description, or Telegram identifier. */
@Component
public class SourceLogCleanupPreviewRenderer {
    static final int MAX_LINE_LENGTH = 2_000;

    public List<String> render(SourceLogCleanupPlan plan) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add(bound("SOURCE_LOG_CLEANUP_PREVIEW status="
                + (plan.previewSafe() ? "SAFE" : "BLOCKED")
                + " readOnly=true writeImplemented=true observedRunning="
                + plan.observedRunningIds().size() + " expectedIds="
                + plan.expectedRunningIds() + " eligible=" + plan.eligibleCount()
                + " rejected=" + plan.rejectedCount() + " preV10=" + plan.preV10Count()
                + " withTenantChildren=" + plan.withTenantChildrenCount()
                + " unfinishedTenantAttempts=" + plan.unfinishedTenantAttempts()
                + " laterSuccessEvidence=" + plan.laterSuccessEvidenceCount()));
        lines.add(bound("SOURCE_LOG_CLEANUP_PREVIEW_PLAN fingerprint=" + plan.fingerprint()
                + " confidence=" + plan.confidenceDistribution()
                + " futureWriteEligible=" + plan.futureWriteEligible()
                + " blockers=" + plan.blockers()));
        lines.add(bound("SOURCE_LOG_CLEANUP_PREVIEW_TRANSACTION isolation="
                + SafeErrorText.token(plan.transactionMode().isolation())
                + " readOnly=" + SafeErrorText.token(plan.transactionMode().readOnly())
                + " minimumAge=" + plan.minimumAge() + " maxCandidates="
                + plan.maxCandidates() + " previewedAt=" + plan.previewedAt()));
        for (SourceLogCleanupPlanEntry entry : plan.entries()) lines.add(candidate(entry));
        for (int index = 0; index < plan.proofBefore().tables().size(); index++) {
            TableProof before = plan.proofBefore().tables().get(index);
            TableProof after = plan.proofAfter().tables().get(index);
            lines.add(bound("SOURCE_LOG_CLEANUP_PREVIEW_PROOF table="
                    + SafeErrorText.token(before.table()) + " beforeCount=" + before.rowCount()
                    + " afterCount=" + after.rowCount() + " beforeFingerprint="
                    + before.fingerprint() + " afterFingerprint=" + after.fingerprint()
                    + " beforeLatest=" + value(before.latestAt()) + " afterLatest="
                    + value(after.latestAt()) + " unchanged=" + before.equals(after)));
        }
        lines.add("SOURCE_LOG_CLEANUP_PREVIEW_COMPLETE readOnly=true writesExecuted=0");
        return List.copyOf(lines);
    }

    private String candidate(SourceLogCleanupPlanEntry entry) {
        return bound("SOURCE_LOG_CLEANUP_PREVIEW_CANDIDATE id=" + entry.id()
                + " source=" + safeSource(entry.sourceName())
                + " run=" + masked(entry.ingestionRunId()) + " startedAt=" + entry.startedAt()
                + " age=" + display(entry.age()) + " current=" + entry.currentStatus()
                + "/finished=" + value(entry.finishedAt()) + " tenantAttempts="
                + entry.tenantSummary().total() + " tenantStates="
                + entry.tenantSummary().stateSummary() + " tenantUnfinished="
                + entry.tenantSummary().unfinished() + " laterTerminal="
                + entry.laterEvidence().terminalCount() + " laterSuccess="
                + entry.laterEvidence().successCount() + " laterLatestId="
                + value(entry.laterEvidence().latestId()) + " laterLatestStatus="
                + value(entry.laterEvidence().latestStatus()) + " laterLatestFinished="
                + value(entry.laterEvidence().latestFinishedAt()) + " eligible="
                + entry.eligible() + " confidence=" + entry.confidence() + " reason="
                + entry.reason() + " proposedStatus=" + entry.proposedStatus()
                + " proposedCategory=" + entry.proposedFailureCategory()
                + " proposedFinishedAt=" + entry.proposedFinishedAtPolicy()
                + " proposedFetchedCount=" + entry.fetchedCount()
                + " proposedSavedCount=" + entry.savedCount() + " proposedErrorSummary=\""
                + entry.proposedErrorSummary() + "\"");
    }

    private String masked(Object value) {
        if (value == null) return "NULL";
        String text = value.toString();
        return text.substring(0, Math.min(8, text.length())) + "...";
    }

    private String safeSource(String value) {
        if (value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            return SafeErrorText.token(value);
        }
        return "redacted-" + Hashing.sha256(value == null ? "NULL" : value).substring(0, 8);
    }

    private String display(Duration duration) {
        return duration.toHours() + "h";
    }

    private String value(Object value) {
        return value == null ? "NULL" : value.toString();
    }

    private String bound(String line) {
        return Utf16.truncate(line, MAX_LINE_LENGTH);
    }
}
