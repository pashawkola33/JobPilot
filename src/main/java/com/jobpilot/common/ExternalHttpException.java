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

    public ExternalHttpException(Category category, Integer statusCode) {
        super(category.name());
        this.category = category;
        this.statusCode = statusCode;
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
