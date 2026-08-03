package com.jobpilot.sources.health;

import com.jobpilot.jobs.domain.RawJob;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The single instrumentation point every ATS adapter uses for one tenant fetch.
 *
 * <p>It times the attempt, classifies any failure, writes the attempt history and health
 * roll-up <em>after</em> the network call completes, and logs one structured safe line.
 * It never throws: a failing tenant yields an empty list so the caller's loop continues,
 * which is exactly the isolation the adapters had before.
 *
 * <p>Failing to persist observability data is logged and swallowed. It must never turn a
 * successful fetch into an ingestion failure, hide the ATS result, or stop later tenants.
 */
@Component
public class TenantFetchMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(TenantFetchMonitor.class);

    private final TenantFailureClassifier classifier;
    private final SourceTenantHealthRecorder recorder;
    private final Clock clock;

    public TenantFetchMonitor(TenantFailureClassifier classifier,
                              SourceTenantHealthRecorder recorder, Clock clock) {
        this.classifier = classifier;
        this.recorder = recorder;
        this.clock = clock;
    }

    /** A monitor that records nothing, for direct adapter construction in tests. */
    public static TenantFetchMonitor disabled() {
        return new TenantFetchMonitor(null, null, Clock.systemUTC());
    }

    /**
     * Runs one tenant fetch under observation.
     *
     * @return the fetched vacancies, or an empty list when the attempt failed.
     */
    public List<RawJob> fetch(String provider, String tenant, Supplier<List<RawJob>> attempt) {
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        List<RawJob> jobs;
        try {
            List<RawJob> fetched = attempt.get();
            jobs = fetched == null ? List.of() : fetched;
        } catch (RuntimeException failure) {
            complete(provider, tenant, TenantAttemptStatus.FAILURE,
                    classify(provider, tenant, failure), 0,
                    elapsedMs(startedNanos), startedAt);
            return List.of();
        }
        TenantAttemptStatus status = jobs.isEmpty()
                ? TenantAttemptStatus.EMPTY_SUCCESS : TenantAttemptStatus.SUCCESS;
        complete(provider, tenant, status, TenantFailure.none(), jobs.size(),
                elapsedMs(startedNanos), startedAt);
        return jobs;
    }

    private TenantFailure classify(String provider, String tenant, RuntimeException failure) {
        if (classifier == null) return TenantFailure.none();
        try {
            return classifier.classify(provider, tenant, failure);
        } catch (RuntimeException classificationFailure) {
            return new TenantFailure(TenantFailureCategory.UNKNOWN_ERROR, null,
                    failure.getClass().getName(), "Tenant fetch failed and could not be classified");
        }
    }

    private void complete(String provider, String tenant, TenantAttemptStatus status,
                          TenantFailure failure, int fetchedCount, long durationMs,
                          Instant startedAt) {
        Instant finishedAt = clock.instant();
        IngestionRunContext context = IngestionRunContext.current();
        UUID runId = context == null ? UUID.randomUUID() : context.runId();
        if (context != null) context.recordAttempt(status, failure.category());

        Integer consecutiveFailures = persist(runId, provider, tenant, status, failure,
                fetchedCount, durationMs, startedAt, finishedAt);
        log(runId, provider, tenant, status, failure, fetchedCount, durationMs, consecutiveFailures);
    }

    /** @return the updated consecutive-failure count, or {@code null} when not persisted. */
    private Integer persist(UUID runId, String provider, String tenant, TenantAttemptStatus status,
                            TenantFailure failure, int fetchedCount, long durationMs,
                            Instant startedAt, Instant finishedAt) {
        if (recorder == null) return null;
        try {
            SourceTenantHealth updated = recorder.record(runId, provider, tenant, status, failure,
                    fetchedCount, durationMs, startedAt, finishedAt);
            return updated == null ? null : updated.getConsecutiveFailures();
        } catch (RuntimeException persistenceFailure) {
            // Observability is best-effort: the ATS result and the remaining tenants stand.
            LOGGER.warn("ATS tenant health not recorded: runId={}, provider={}, tenant={}, "
                            + "reason={}", runId, SafeErrorText.token(provider),
                    SafeErrorText.token(tenant), persistenceFailure.getClass().getSimpleName());
            return null;
        }
    }

    private void log(UUID runId, String provider, String tenant, TenantAttemptStatus status,
                     TenantFailure failure, int fetchedCount, long durationMs,
                     Integer consecutiveFailures) {
        String safeProvider = SafeErrorText.token(provider);
        String safeTenant = SafeErrorText.token(tenant);
        if (status.successful()) {
            LOGGER.info("ATS tenant fetch succeeded: runId={}, provider={}, tenant={}, status={}, "
                            + "fetched={}, durationMs={}",
                    runId, safeProvider, safeTenant, status, fetchedCount, durationMs);
            return;
        }
        LOGGER.warn("ATS tenant fetch failed: runId={}, provider={}, tenant={}, category={}, "
                        + "httpStatus={}, durationMs={}, consecutiveFailures={}, detail={}",
                runId, safeProvider, safeTenant, failure.category(), failure.httpStatus(),
                durationMs, consecutiveFailures, failure.errorMessage());
    }

    private long elapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }
}
