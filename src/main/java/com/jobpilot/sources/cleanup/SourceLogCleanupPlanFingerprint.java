package com.jobpilot.sources.cleanup;

import com.jobpilot.common.Hashing;
import com.jobpilot.sources.cleanup.SourceLogCleanupProperties.Guards;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Versioned, length-prefixed canonical SHA-256 identity for a source-log cleanup plan. */
public final class SourceLogCleanupPlanFingerprint {
    public static final String FORMAT_VERSION = "jobpilot-source-log-cleanup-plan-v1";

    private SourceLogCleanupPlanFingerprint() {
    }

    public static String fingerprint(Guards guards, List<Long> observedIds,
                                     List<SourceLogCleanupPlanEntry> entries,
                                     List<String> blockers) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            field(output, "format", FORMAT_VERSION);
            field(output, "minimumAge", guards.minimumAge().toString());
            field(output, "maximumCandidates", Integer.toString(guards.maxCandidates()));
            longs(output, "expectedId", guards.expectedRunningIds());
            field(output, "expectedCount", guards.expectedRunningCount() == null ? null
                    : guards.expectedRunningCount().toString());
            longs(output, "observedId", observedIds);
            List<SourceLogCleanupPlanEntry> ordered = entries.stream()
                    .sorted(Comparator.comparingLong(SourceLogCleanupPlanEntry::id)).toList();
            field(output, "entryCount", Integer.toString(ordered.size()));
            for (SourceLogCleanupPlanEntry entry : ordered) append(output, entry);
            field(output, "blockerCount", Integer.toString(blockers.size()));
            for (String blocker : blockers) field(output, "blocker", blocker);
            output.flush();
            return Hashing.sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not fingerprint in-memory cleanup plan", impossible);
        }
    }

    private static void append(DataOutputStream output, SourceLogCleanupPlanEntry entry)
            throws IOException {
        field(output, "id", Long.toString(entry.id()));
        field(output, "ingestionRunId", entry.ingestionRunId() == null ? null
                : entry.ingestionRunId().toString());
        field(output, "sourceName", entry.sourceName());
        field(output, "status", entry.currentStatus());
        field(output, "startedAt", text(entry.startedAt()));
        field(output, "finishedAt", text(entry.finishedAt()));
        field(output, "fetchedCount", Integer.toString(entry.fetchedCount()));
        field(output, "savedCount", Integer.toString(entry.savedCount()));
        field(output, "existingErrorSummary", entry.existingErrorSummary());
        field(output, "activeOwnerEvidence", Boolean.toString(entry.activeOwnerEvidence()));
        field(output, "tenantCount", Long.toString(entry.tenantSummary().total()));
        field(output, "tenantUnfinished", Long.toString(entry.tenantSummary().unfinished()));
        for (Map.Entry<String, Long> state : entry.tenantSummary().terminalStates().entrySet()
                .stream().sorted(Map.Entry.comparingByKey()).toList()) {
            field(output, "tenantState", state.getKey() + "=" + state.getValue());
        }
        field(output, "laterTerminalCount",
                Long.toString(entry.laterEvidence().terminalCount()));
        field(output, "laterSuccessCount", Long.toString(entry.laterEvidence().successCount()));
        field(output, "laterLatestId", entry.laterEvidence().latestId() == null ? null
                : entry.laterEvidence().latestId().toString());
        field(output, "laterLatestStatus", entry.laterEvidence().latestStatus());
        field(output, "laterLatestFinishedAt", text(entry.laterEvidence().latestFinishedAt()));
        field(output, "eligible", Boolean.toString(entry.eligible()));
        field(output, "confidence", entry.confidence().name());
        field(output, "reason", entry.reason());
        field(output, "proposedStatus", entry.proposedStatus());
        field(output, "proposedFailureCategory", entry.proposedFailureCategory());
        field(output, "proposedFinishedAtPolicy", entry.proposedFinishedAtPolicy());
        field(output, "proposedFetchedCount", Integer.toString(entry.fetchedCount()));
        field(output, "proposedSavedCount", Integer.toString(entry.savedCount()));
        field(output, "proposedErrorSummary", entry.proposedErrorSummary());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static void longs(DataOutputStream output, String name, List<Long> values)
            throws IOException {
        field(output, name + "Count", Integer.toString(values.size()));
        for (Long value : values) field(output, name, value.toString());
    }

    private static void field(DataOutputStream output, String name, String value)
            throws IOException {
        write(output, name);
        output.writeBoolean(value != null);
        if (value != null) write(output, value);
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
