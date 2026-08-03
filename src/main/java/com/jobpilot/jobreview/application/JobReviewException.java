package com.jobpilot.jobreview.application;

/** Carries only operator-safe text; never a provider payload, SQL fragment, or secret. */
public class JobReviewException extends RuntimeException {
    public enum Category { JOB_NOT_FOUND, INVALID_WORKFLOW }

    private final Category category;

    public JobReviewException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
