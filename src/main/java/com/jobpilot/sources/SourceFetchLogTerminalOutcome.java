package com.jobpilot.sources;

/** What a terminal transition actually did, so callers can never assume success. */
public enum SourceFetchLogTerminalOutcome {
    /** The row moved from RUNNING to the requested terminal status. */
    UPDATED,
    /** The row exists but was already terminal; this transition changed nothing. */
    ALREADY_TERMINAL,
    /** No row with that id exists any more. */
    MISSING,
    /** Every bounded attempt to write the terminal status failed. */
    FAILED_TO_PERSIST;

    public boolean finalized() {
        return this == UPDATED;
    }
}
