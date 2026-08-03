package com.jobpilot.sources.health;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one completed tenant attempt: an immutable history row plus the current
 * roll-up.
 *
 * <p>Deliberately a separate bean from {@link TenantFetchMonitor} so the Spring proxy
 * applies and the transaction opens only <em>after</em> the external HTTP call has
 * finished. {@code REQUIRES_NEW} keeps a write failure here isolated from any caller
 * transaction and from the ingestion result.
 */
@Service
public class SourceTenantHealthRecorder {
    private final SourceTenantFetchLogRepository attempts;
    private final SourceTenantHealthRepository health;

    public SourceTenantHealthRecorder(SourceTenantFetchLogRepository attempts,
                                      SourceTenantHealthRepository health) {
        this.attempts = attempts;
        this.health = health;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceTenantHealth record(UUID runId, String provider, String tenant,
                                     TenantAttemptStatus status, TenantFailure failure,
                                     int fetchedCount, long durationMs,
                                     Instant startedAt, Instant finishedAt) {
        attempts.save(new SourceTenantFetchLog(runId, provider, tenant, status, failure,
                fetchedCount, durationMs, startedAt, finishedAt));

        String safeProvider = SafeErrorText.token(provider);
        String safeTenant = SafeErrorText.token(tenant);
        SourceTenantHealth current = health.findByProviderAndTenant(safeProvider, safeTenant)
                .orElseGet(() -> new SourceTenantHealth(safeProvider, safeTenant, startedAt));
        current.apply(status, failure, fetchedCount, durationMs, finishedAt);
        return health.save(current);
    }
}
