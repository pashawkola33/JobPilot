package com.jobpilot.telegram.commands;

import com.jobpilot.applications.domain.ApplicationStatus;
import java.time.Instant;
import java.time.LocalDate;

public record TelegramCommand(
        Kind kind,
        Long jobId,
        String text,
        Instant instant,
        LocalDate date,
        ApplicationStatus statusFilter) {
    public enum Kind {
        HELP, ADD, SAVE, APPLIED, INTERVIEW, REJECTED, OFFER, WITHDRAW,
        FOLLOWUP, NOTE, STATUS, APPLICATIONS
    }

    public static TelegramCommand simple(Kind kind) {
        return new TelegramCommand(kind, null, null, null, null, null);
    }
}
