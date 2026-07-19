package com.jobpilot.telegram.commands;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.resume.domain.DocumentFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

public record TelegramCommand(
        Kind kind,
        Long jobId,
        Long resumeVersionId,
        Long coverNoteId,
        DocumentScope documentScope,
        Set<DocumentFormat> documentFormats,
        String text,
        Instant instant,
        LocalDate date,
        ApplicationStatus statusFilter) {
    public enum Kind {
        HELP, ADD, SAVE, APPLIED, INTERVIEW, REJECTED, OFFER, WITHDRAW,
        FOLLOWUP, NOTE, STATUS, APPLICATIONS, HISTORY,
        ANALYZE, DOCUMENTS, RESUMES, COVER_NOTES, SELECT_DOCUMENTS
    }

    public enum DocumentScope {
        RESUME,
        ALL
    }

    public TelegramCommand(Kind kind, Long jobId, String text, Instant instant,
                           LocalDate date, ApplicationStatus statusFilter) {
        this(kind, jobId, null, null, null, Set.of(), text, instant, date, statusFilter);
    }

    public static TelegramCommand simple(Kind kind) {
        return new TelegramCommand(kind, null, null, null, null, Set.of(),
                null, null, null, null);
    }
}
