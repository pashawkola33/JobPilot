package com.jobpilot.jobs.domain;

/** Final or stage-level outcome of deterministic vacancy screening. */
public enum ScreeningDisposition {
    MATCH,
    REVIEW,
    REJECT;

    public boolean persistable() {
        return this != REJECT;
    }

    public static ScreeningDisposition combine(ScreeningDisposition... dispositions) {
        boolean review = false;
        for (ScreeningDisposition disposition : dispositions) {
            if (disposition == null || disposition == REVIEW) review = true;
            if (disposition == REJECT) return REJECT;
        }
        return review ? REVIEW : MATCH;
    }
}
