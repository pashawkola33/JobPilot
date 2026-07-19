package com.jobpilot.applications.application;

public class ApplicationTrackingException extends RuntimeException {
    public enum Category {
        JOB_NOT_FOUND,
        APPLICATION_NOT_FOUND,
        INVALID_TRANSITION,
        INVALID_VALUE,
        CONFLICT
    }

    private final Category category;

    public ApplicationTrackingException(Category category, String safeMessage) {
        super(safeMessage);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
