package com.jobpilot.sources;

/**
 * A source finished but its log row could not be moved out of RUNNING.
 *
 * <p>Carries the outcome and bounded identity only. Raised so a run can never report a
 * source as finalized when the terminal write did not happen.
 */
public final class SourceFetchLogTerminalizationException extends RuntimeException {
    private final SourceFetchLogTerminalOutcome outcome;
    private final long logId;

    public SourceFetchLogTerminalizationException(SourceFetchLogTerminalOutcome outcome,
                                                  long logId, String sourceName) {
        super("Source fetch log " + logId + " for source "
                + com.jobpilot.sources.health.SafeErrorText.token(sourceName)
                + " could not be finalized: " + outcome);
        this.outcome = outcome;
        this.logId = logId;
    }

    public SourceFetchLogTerminalOutcome outcome() {
        return outcome;
    }

    public long logId() {
        return logId;
    }
}
