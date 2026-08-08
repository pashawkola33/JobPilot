package com.jobpilot.miniapp.api;

/**
 * Same {@code category} + {@code message} shape as the other JobPilot APIs. Messages are
 * operator-safe constants: they never say which validation step failed, so a caller cannot
 * probe the hash check, and they never carry a token, hash, id, or provider payload.
 */
public record MiniAppApiError(String category, String message) {

    public static final String DISABLED = "MINI_APP_DISABLED";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String INVALID_AUTH = "INVALID_AUTH";
    public static final String EXPIRED_AUTH = "EXPIRED_AUTH";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String JOB_NOT_FOUND = "JOB_NOT_FOUND";
    public static final String INVALID_WORKFLOW = "INVALID_WORKFLOW";
    /** The same mutation id arrived describing a different operation — a client bug, not a retry. */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    /** The durable state moved, so this undo no longer describes it. The client reconciles. */
    public static final String UNDO_STALE = "UNDO_STALE";

    public static MiniAppApiError disabled() {
        return new MiniAppApiError(DISABLED, "The Mini App API is not enabled on this server.");
    }

    public static MiniAppApiError unauthenticated() {
        return new MiniAppApiError(UNAUTHENTICATED, "Telegram authentication is required.");
    }

    /** One message for every rejected payload: malformed, tampered, or wrongly signed. */
    public static MiniAppApiError invalidAuth() {
        return new MiniAppApiError(INVALID_AUTH, "Telegram authentication could not be verified.");
    }

    public static MiniAppApiError expiredAuth() {
        return new MiniAppApiError(EXPIRED_AUTH, "Telegram authentication has expired. Reopen the app.");
    }

    public static MiniAppApiError forbidden() {
        return new MiniAppApiError(FORBIDDEN, "This Telegram account is not allowed to use JobPilot.");
    }
}
