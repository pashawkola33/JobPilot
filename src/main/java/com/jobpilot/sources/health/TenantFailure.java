package com.jobpilot.sources.health;

/**
 * Classified, already-sanitised description of one tenant fetch failure.
 * Construction always bounds and redacts, so the values are safe to persist and expose.
 */
public record TenantFailure(
        TenantFailureCategory category,
        Integer httpStatus,
        String errorType,
        String errorMessage) {

    public TenantFailure {
        category = category == null ? TenantFailureCategory.UNKNOWN_ERROR : category;
        httpStatus = httpStatus != null && httpStatus >= 100 && httpStatus <= 599 ? httpStatus : null;
        errorType = SafeErrorText.type(errorType);
        errorMessage = SafeErrorText.message(errorMessage);
    }

    public static TenantFailure none() {
        return new TenantFailure(TenantFailureCategory.NONE, null, null, null);
    }
}
