package com.jobpilot.sources;

import java.util.UUID;

/**
 * Immutable identity of one in-flight source fetch log row.
 *
 * <p>Deliberately not the entity. Holding a mutable {@code SourceFetchLog} across minutes of
 * network work is what let a detached, never-flushed instance leave a row RUNNING forever;
 * this carries only bounded identity, so the terminal write is always a fresh conditional
 * update rather than a save of stale in-memory state.
 */
public record SourceFetchLogHandle(long id, String sourceName, UUID ingestionRunId) {
    public SourceFetchLogHandle {
        if (sourceName == null || sourceName.isBlank()) {
            throw new IllegalArgumentException("Source name is required");
        }
    }
}
