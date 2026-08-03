package com.jobpilot.common;

/** Fixed-category transport failure that never embeds a request URL or body. */
public final class ExternalHttpException extends RuntimeException {
    public enum Category {
        INVALID_DESTINATION,
        REDIRECT_LIMIT,
        INVALID_CONTENT_TYPE,
        RESPONSE_TOO_LARGE,
        HTTP_STATUS,
        TIMEOUT,
        IO,
        MALFORMED_JSON
    }

    private final Category category;
    private final Integer statusCode;
    private Long retryAfterSeconds;
    private Integer limitBytes;

    public ExternalHttpException(Category category, Integer statusCode) {
        super(category.name());
        this.category = category;
        this.statusCode = statusCode;
    }

    /**
     * The configured response-size limit that a {@code RESPONSE_TOO_LARGE} failure
     * breached. Structured so callers never parse it out of a message string. It is a
     * configured number, never a measured body length, so it leaks nothing about the
     * response itself.
     */
    public Integer limitBytes() {
        return limitBytes;
    }

    public ExternalHttpException limitBytes(Integer value) {
        this.limitBytes = value;
        return this;
    }

    public Category category() {
        return category;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ExternalHttpException retryAfterSeconds(Long value) {
        this.retryAfterSeconds = value;
        return this;
    }
}
