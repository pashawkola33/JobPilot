package com.jobpilot.manualurl.fetch;

public class ManualUrlValidationException extends RuntimeException {
    public enum Reason {
        INVALID,
        PROHIBITED_DESTINATION,
        RESOLUTION_FAILED
    }

    private final Reason reason;

    public ManualUrlValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ManualUrlValidationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
