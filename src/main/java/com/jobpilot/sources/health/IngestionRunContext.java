package com.jobpilot.sources.health;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Correlates one complete ingestion run across the aggregate source log, every tenant
 * attempt log, and the final summary lines.
 *
 * <p>Sources are fetched sequentially on the caller's thread, so a thread-local carrier
 * keeps {@link com.jobpilot.sources.JobSource} free of an observability parameter. When
 * no run is open (a direct adapter call, or a test), callers fall back to a fresh id and
 * a throwaway summary, so instrumentation never depends on the context being present.
 *
 * <p>The run id is diagnostic only: it is never used for job identity or deduplication.
 */
public final class IngestionRunContext {
    private static final ThreadLocal<IngestionRunContext> CURRENT = new ThreadLocal<>();

    private final UUID runId;
    private final Map<TenantAttemptStatus, Integer> attempts =
            new EnumMap<>(TenantAttemptStatus.class);
    private final Map<TenantFailureCategory, Integer> failures =
            new EnumMap<>(TenantFailureCategory.class);

    private IngestionRunContext(UUID runId) {
        this.runId = runId;
    }

    /** Opens a run on the current thread. Always pair with {@link #clear()} in a finally block. */
    public static IngestionRunContext open() {
        IngestionRunContext context = new IngestionRunContext(UUID.randomUUID());
        CURRENT.set(context);
        return context;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** The open run, or {@code null} when the caller is outside a run. */
    public static IngestionRunContext current() {
        return CURRENT.get();
    }

    /** The open run's id, or a fresh id when no run is open. */
    public static UUID currentRunId() {
        IngestionRunContext context = CURRENT.get();
        return context == null ? UUID.randomUUID() : context.runId();
    }

    public UUID runId() {
        return runId;
    }

    void recordAttempt(TenantAttemptStatus status, TenantFailureCategory category) {
        attempts.merge(status, 1, Integer::sum);
        if (status == TenantAttemptStatus.FAILURE) {
            failures.merge(category == null ? TenantFailureCategory.UNKNOWN_ERROR : category,
                    1, Integer::sum);
        }
    }

    public int count(TenantAttemptStatus status) {
        return attempts.getOrDefault(status, 0);
    }

    public int totalAttempts() {
        return attempts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Failure counts by category, in declaration order, omitting zero entries. */
    public Map<String, Integer> failuresByCategory() {
        Map<String, Integer> result = new LinkedHashMap<>();
        failures.forEach((category, count) -> result.put(category.name(), count));
        return result;
    }
}
