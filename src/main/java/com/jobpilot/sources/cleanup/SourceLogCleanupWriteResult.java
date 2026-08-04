package com.jobpilot.sources.cleanup;

import java.time.Instant;
import java.util.List;

/** Bounded one-shot result containing no source payload or pre-existing error text. */
public record SourceLogCleanupWriteResult(
        Status status,
        String safeReason,
        int rowsUpdated,
        List<Long> ids,
        Instant finishedAt) {

    public enum Status { SUCCESS, ERROR }

    public SourceLogCleanupWriteResult {
        ids = List.copyOf(ids);
    }

    public static SourceLogCleanupWriteResult success(int rowsUpdated, List<Long> ids,
                                                       Instant finishedAt) {
        return new SourceLogCleanupWriteResult(
                Status.SUCCESS, null, rowsUpdated, ids, finishedAt);
    }

    public static SourceLogCleanupWriteResult error(String safeReason) {
        return new SourceLogCleanupWriteResult(
                Status.ERROR, safeReason, 0, List.of(), null);
    }
}
