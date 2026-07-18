package com.jobpilot.manualurl.fetch;

public class ManualFetchException extends RuntimeException {
    public enum Category {
        TIMEOUT,
        RESPONSE_TOO_LARGE,
        UNSUPPORTED_CONTENT_TYPE,
        REDIRECT_LIMIT,
        INVALID_REDIRECT,
        BLOCKED_OR_PROTECTED,
        HTTP_FAILURE,
        NETWORK_FAILURE
    }

    private final Category category;

    public ManualFetchException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public ManualFetchException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }
}
