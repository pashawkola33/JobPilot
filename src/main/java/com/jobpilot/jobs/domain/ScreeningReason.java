package com.jobpilot.jobs.domain;

public record ScreeningReason(ScreeningStage stage, String code, String message) {
    public ScreeningReason {
        if (stage == null) throw new IllegalArgumentException("Screening reason stage is required");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("Screening reason code is required");
        if (message == null || message.isBlank()) throw new IllegalArgumentException("Screening reason message is required");
        code = code.strip();
        message = message.strip();
    }
}
