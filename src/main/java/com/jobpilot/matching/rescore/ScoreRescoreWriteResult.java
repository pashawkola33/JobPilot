package com.jobpilot.matching.rescore;

import java.time.Instant;
import java.util.List;

/** Bounded command outcome; it contains no vacancy or candidate text. */
public record ScoreRescoreWriteResult(
        Status status,
        String safeMessage,
        int scoreRowsUpdated,
        int requirementRowsUpdated,
        List<Long> jobIds,
        Instant scoredAt) {

    public enum Status { SUCCESS, ERROR }

    public static ScoreRescoreWriteResult success(int scores, int requirements,
                                                  List<Long> jobIds, Instant scoredAt) {
        return new ScoreRescoreWriteResult(Status.SUCCESS, null, scores, requirements,
                List.copyOf(jobIds), scoredAt);
    }

    public static ScoreRescoreWriteResult error(String safeMessage) {
        return new ScoreRescoreWriteResult(Status.ERROR, safeMessage, 0, 0, List.of(), null);
    }
}
