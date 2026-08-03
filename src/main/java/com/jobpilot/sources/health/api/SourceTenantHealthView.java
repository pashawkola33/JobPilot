package com.jobpilot.sources.health.api;

import com.jobpilot.sources.health.SourceTenantHealth;
import java.time.Instant;

/**
 * Read-only per-tenant diagnostics. Contains no credential, header, query string,
 * response body, or stack trace: every string here was sanitised before persistence.
 */
public record SourceTenantHealthView(
        String provider,
        String tenant,
        boolean healthy,
        boolean degraded,
        String lastStatus,
        String lastFailureCategory,
        Integer lastHttpStatus,
        int lastFetchedCount,
        long lastDurationMs,
        int consecutiveFailures,
        long totalAttempts,
        long totalSuccesses,
        long totalFailures,
        Instant lastAttemptAt,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        String safeErrorType,
        String safeErrorMessage) {

    public static SourceTenantHealthView of(SourceTenantHealth health) {
        return new SourceTenantHealthView(
                health.getProvider(),
                health.getTenant(),
                health.healthy(),
                health.degraded(),
                health.getLastAttemptStatus() == null ? null : health.getLastAttemptStatus().name(),
                health.getLastFailureCategory() == null ? null : health.getLastFailureCategory().name(),
                health.getLastHttpStatus(),
                health.getLastFetchedCount(),
                health.getLastDurationMs(),
                health.getConsecutiveFailures(),
                health.getTotalAttempts(),
                health.getTotalSuccesses(),
                health.getTotalFailures(),
                health.getLastAttemptAt(),
                health.getLastSuccessAt(),
                health.getLastFailureAt(),
                health.getLastErrorType(),
                health.getLastErrorMessage());
    }
}
