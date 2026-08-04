package com.jobpilot.sources.cleanup;

import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.LaterTerminalEvidence;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TenantSummary;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Immutable evidence and non-executable proposal for one observed RUNNING row. */
public record SourceLogCleanupPlanEntry(
        long id,
        UUID ingestionRunId,
        String sourceName,
        Instant startedAt,
        Instant finishedAt,
        Duration age,
        String currentStatus,
        int fetchedCount,
        int savedCount,
        String existingErrorSummary,
        TenantSummary tenantSummary,
        LaterTerminalEvidence laterEvidence,
        boolean activeOwnerEvidence,
        boolean eligible,
        Confidence confidence,
        String reason,
        String proposedStatus,
        String proposedFailureCategory,
        String proposedFinishedAtPolicy,
        String proposedErrorSummary) {

    public enum Confidence { HIGH, MODERATE }
}
