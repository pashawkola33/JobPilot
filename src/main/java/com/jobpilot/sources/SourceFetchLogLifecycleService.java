package com.jobpilot.sources;

import com.jobpilot.common.Utf16;
import com.jobpilot.sources.health.SafeErrorText;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

/**
 * The lifecycle boundary for one source fetch log row: RUNNING in, terminal out.
 *
 * <p>The retry loop lives here rather than inside the writer on purpose. {@code REQUIRES_NEW}
 * on a method that also loops would put every attempt in the same transaction, so the loop
 * must sit outside and call a separately proxied bean to get a genuinely new transaction per
 * attempt.
 *
 * <p>Honest boundary: this hardens every failure the JVM can still observe — a thrown
 * exception, an Error, a graceful interrupt, a transient database blip. A SIGKILL, host loss,
 * or a database that stays unreachable through all attempts can still leave a row RUNNING,
 * and nothing in this class pretends otherwise.
 */
@Service
public class SourceFetchLogLifecycleService {
    /** Bounded: a terminal write that fails three times is not going to succeed by looping. */
    public static final int MAX_TERMINAL_ATTEMPTS = 3;
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_FAILED = "FAILED";
    /** Matches source_fetch_logs.error_summary. */
    private static final int MAX_ERROR_SUMMARY = 500;

    private static final Logger LOGGER =
            LoggerFactory.getLogger(SourceFetchLogLifecycleService.class);

    private final SourceFetchLogTerminalWriter writer;
    private final SourceFetchExecutionRegistry executions;
    private final Duration retryDelay;

    @org.springframework.beans.factory.annotation.Autowired
    public SourceFetchLogLifecycleService(SourceFetchLogTerminalWriter writer,
                                          SourceFetchExecutionRegistry executions) {
        this(writer, executions, Duration.ofMillis(200));
    }

    /** Test seam: a zero delay keeps retry tests fast without touching the attempt count. */
    public SourceFetchLogLifecycleService(SourceFetchLogTerminalWriter writer, Duration retryDelay) {
        this(writer, new SourceFetchExecutionRegistry(), retryDelay);
    }

    SourceFetchLogLifecycleService(SourceFetchLogTerminalWriter writer,
                                   SourceFetchExecutionRegistry executions,
                                   Duration retryDelay) {
        this.writer = writer;
        this.executions = executions;
        this.retryDelay = retryDelay;
    }

    /**
     * Opens the row. Deliberately not retried: the initial insert is the caller's signal that
     * the database is usable at all, and a failure here happens before any source work, so
     * there is nothing yet to orphan.
     */
    public SourceFetchLogHandle begin(String sourceName, UUID ingestionRunId, Instant startedAt) {
        SourceFetchLogHandle handle = writer.begin(sourceName, ingestionRunId, startedAt);
        executions.register(handle);
        return handle;
    }

    public SourceFetchLogTerminalOutcome succeed(SourceFetchLogHandle handle, int fetched,
                                                 int saved, Instant finishedAt) {
        return terminalize(handle, STATUS_SUCCESS, finishedAt, fetched, saved, null);
    }

    public SourceFetchLogTerminalOutcome fail(SourceFetchLogHandle handle,
                                              SourceFetchFailureCategory category,
                                              Throwable failure, Instant finishedAt) {
        return terminalize(handle, STATUS_FAILED, finishedAt, 0, 0, summary(category, failure));
    }

    /**
     * Bounded, safe {@code error_summary}: a closed category plus a sanitised type name, for
     * example {@code PROCESS_INTERRUPTED: ExternalHttpException}. The exception message is
     * never read, because transport messages embed URLs and URLs embed credentials.
     */
    static String summary(SourceFetchFailureCategory category, Throwable failure) {
        String type = failure == null ? null : SafeErrorText.type(failure.getClass().getSimpleName());
        String text = category.name() + (type == null || type.isBlank() ? "" : ": " + type);
        return Utf16.truncate(text, MAX_ERROR_SUMMARY);
    }

    private SourceFetchLogTerminalOutcome terminalize(SourceFetchLogHandle handle, String status,
                                                      Instant finishedAt, int fetched, int saved,
                                                      String errorSummary) {
        try {
            return terminalizeWithRetry(handle, status, finishedAt, fetched, saved, errorSummary);
        } finally {
            executions.unregister(handle);
        }
    }

    private SourceFetchLogTerminalOutcome terminalizeWithRetry(
            SourceFetchLogHandle handle, String status, Instant finishedAt, int fetched, int saved,
            String errorSummary) {
        DataAccessException lastTransient = null;
        for (int attempt = 1; attempt <= MAX_TERMINAL_ATTEMPTS; attempt++) {
            try {
                int updated = writer.terminalize(handle.id(), status, finishedAt, fetched, saved,
                        errorSummary);
                if (updated > 0) return SourceFetchLogTerminalOutcome.UPDATED;
                // Zero rows means the predicate did not hold: either somebody already
                // finalised it, or the row is gone. Both are reported, never ignored.
                return writer.exists(handle.id())
                        ? SourceFetchLogTerminalOutcome.ALREADY_TERMINAL
                        : SourceFetchLogTerminalOutcome.MISSING;
            } catch (DataAccessException databaseFailure) {
                if (!transient_(databaseFailure)) {
                    // A constraint violation or malformed statement will never succeed by
                    // repeating; retrying would only delay the honest answer.
                    LOGGER.error("Source fetch log {} could not be finalized for source {} "
                                    + "category=non_transient attempt={} type={}",
                            handle.id(), SafeErrorText.token(handle.sourceName()), attempt,
                            databaseFailure.getClass().getName());
                    return SourceFetchLogTerminalOutcome.FAILED_TO_PERSIST;
                }
                lastTransient = databaseFailure;
                if (attempt < MAX_TERMINAL_ATTEMPTS) pause();
            }
        }
        LOGGER.error("Source fetch log {} could not be finalized for source {} "
                        + "category=transient_exhausted attempts={} type={}",
                handle.id(), SafeErrorText.token(handle.sourceName()), MAX_TERMINAL_ATTEMPTS,
                lastTransient == null ? "unknown" : lastTransient.getClass().getName());
        return SourceFetchLogTerminalOutcome.FAILED_TO_PERSIST;
    }

    /** Only Spring's own transient markers are retried; everything else fails closed. */
    private boolean transient_(DataAccessException failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TransientDataAccessException
                    || current instanceof RecoverableDataAccessException) {
                return true;
            }
            if (current.getCause() == current) break;
        }
        return false;
    }

    private void pause() {
        if (retryDelay.isZero() || retryDelay.isNegative()) return;
        try {
            Thread.sleep(retryDelay.toMillis());
        } catch (InterruptedException interrupted) {
            // Preserve the flag: the caller decides what an interrupt means, not this retry.
            Thread.currentThread().interrupt();
        }
    }
}
