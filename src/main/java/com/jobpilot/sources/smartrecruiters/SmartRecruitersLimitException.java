package com.jobpilot.sources.smartrecruiters;

/** Structured deterministic cap failure; never carries a URL, response, or posting data. */
public final class SmartRecruitersLimitException extends RuntimeException {
    public enum Limit { LIST_PAGES, UNIQUE_POSTINGS }

    private final Limit limit;
    private final int configuredMaximum;

    public SmartRecruitersLimitException(Limit limit, int configuredMaximum) {
        super("SMARTRECRUITERS_" + limit.name() + "_LIMIT");
        this.limit = limit;
        this.configuredMaximum = configuredMaximum;
    }

    public Limit limit() {
        return limit;
    }

    public int configuredMaximum() {
        return configuredMaximum;
    }
}
