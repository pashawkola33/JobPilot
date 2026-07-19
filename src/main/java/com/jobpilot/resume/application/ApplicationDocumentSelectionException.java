package com.jobpilot.resume.application;

public class ApplicationDocumentSelectionException extends RuntimeException {
    private final Category category;

    public ApplicationDocumentSelectionException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }

    public enum Category {
        APPLICATION_NOT_FOUND,
        RESUME_NOT_FOUND,
        COVER_NOTE_NOT_FOUND,
        WRONG_JOB,
        INCOMPATIBLE_PROFILE,
        INCOMPATIBLE_COVER_NOTE,
        ARTIFACT_INVALID,
        CONFLICT
    }
}
