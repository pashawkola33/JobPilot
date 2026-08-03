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
    private String parseDetail;

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

    /**
     * Which schema rule rejected a {@code MALFORMED_JSON} response, as a bounded
     * provider-generic phrase such as {@code "detail.jobAd: expected OBJECT but was
     * MISSING"}. It is built only from compile-time field paths and JSON <em>type</em>
     * names, never from a field value, response fragment, URL, or header, so it is safe to
     * persist as operator-facing health text. Null when the caller supplied no context.
     */
    public String parseDetail() {
        return parseDetail;
    }

    public ExternalHttpException parseDetail(String value) {
        this.parseDetail = value;
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
