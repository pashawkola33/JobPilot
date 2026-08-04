package com.jobpilot.sources.workday;

/** Structured deterministic cap failure; never carries a URL, response, or posting data. */
public final class WorkdayLimitException extends RuntimeException {
    public enum Limit { LIST_PAGES, UNIQUE_POSTINGS, DETAIL_REQUESTS, RUNTIME_SECONDS }

    private final Limit limit;
    private final long configuredMaximum;

    public WorkdayLimitException(Limit limit, long configuredMaximum) {
        super("WORKDAY_" + limit.name() + "_LIMIT");
        this.limit = limit;
        this.configuredMaximum = configuredMaximum;
    }

    public Limit limit() {
        return limit;
    }

    public long configuredMaximum() {
        return configuredMaximum;
    }
}
