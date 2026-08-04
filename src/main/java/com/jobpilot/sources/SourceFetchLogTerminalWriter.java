package com.jobpilot.sources;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place that opens a transaction for a source fetch log row.
 *
 * <p>Every method is {@code REQUIRES_NEW} so a begin or a terminal write commits on its own,
 * independently of whatever transaction the caller is in and independently of each retry
 * attempt. That independence is the point: a terminal write must still land when the
 * surrounding work has already failed.
 */
@Component
public class SourceFetchLogTerminalWriter {
    private final SourceFetchLogRepository logs;

    public SourceFetchLogTerminalWriter(SourceFetchLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SourceFetchLogHandle begin(String sourceName, UUID ingestionRunId, Instant startedAt) {
        SourceFetchLog saved = logs.saveAndFlush(
                new SourceFetchLog(sourceName, startedAt, ingestionRunId));
        return new SourceFetchLogHandle(saved.getId(), sourceName, ingestionRunId);
    }

    /** @return 1 when this call moved the row out of RUNNING, 0 otherwise. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int terminalize(long id, String status, Instant finishedAt, int fetched, int saved,
                           String errorSummary) {
        return logs.terminalize(id, status, finishedAt, fetched, saved, errorSummary);
    }

    /** Distinguishes ALREADY_TERMINAL from MISSING once an update has affected no rows. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean exists(long id) {
        return logs.existsById(id);
    }
}
