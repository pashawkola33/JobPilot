package com.jobpilot.telegram.commands;

import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.config.JobPilotProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import com.jobpilot.resume.domain.DocumentFormat;
import org.springframework.stereotype.Component;

@Component
public class TelegramCommandParser {
    public static final int MAX_COMMAND_LENGTH = 4096;
    private final String botUsername;

    public TelegramCommandParser(JobPilotProperties properties) {
        this.botUsername = properties.telegram().botUsername();
    }

    public TelegramCommandParseResult parse(String raw) {
        if (raw == null || raw.isBlank() || !raw.stripLeading().startsWith("/")) {
            return TelegramCommandParseResult.failure("Send /help to see available commands.");
        }
        String input = raw.strip();
        if (input.length() > MAX_COMMAND_LENGTH) {
            return TelegramCommandParseResult.failure("Command is too long.");
        }
        int split = firstWhitespace(input);
        String token = split < 0 ? input : input.substring(0, split);
        String args = split < 0 ? "" : input.substring(split).strip();
        int suffix = token.indexOf('@');
        if (suffix >= 0) {
            String addressedBot = token.substring(suffix + 1);
            if (suffix == 0 || addressedBot.isEmpty() || addressedBot.indexOf('@') >= 0
                    || !addressedBot.equalsIgnoreCase(botUsername)) {
                return TelegramCommandParseResult.success(
                        TelegramCommand.simple(TelegramCommand.Kind.HELP));
            }
            token = token.substring(0, suffix);
        }
        token = token.toLowerCase(Locale.ROOT);
        return switch (token) {
            case "/help" -> noArgs(args, TelegramCommand.Kind.HELP, "Usage: /help");
            case "/add" -> requiredText(args, TelegramCommand.Kind.ADD, "Usage: /add &lt;public vacancy URL&gt;");
            case "/save" -> jobOnly(args, TelegramCommand.Kind.SAVE, "Usage: /save &lt;jobId&gt;");
            case "/applied" -> jobOnly(args, TelegramCommand.Kind.APPLIED, "Usage: /applied &lt;jobId&gt;");
            case "/offer" -> jobOnly(args, TelegramCommand.Kind.OFFER, "Usage: /offer &lt;jobId&gt;");
            case "/withdraw" -> jobOnly(args, TelegramCommand.Kind.WITHDRAW, "Usage: /withdraw &lt;jobId&gt;");
            case "/status" -> jobOnly(args, TelegramCommand.Kind.STATUS, "Usage: /status &lt;jobId&gt;");
            case "/history" -> jobOnly(args, TelegramCommand.Kind.HISTORY,
                    "Usage: /history &lt;jobId&gt;");
            case "/analyze" -> jobOnly(args, TelegramCommand.Kind.ANALYZE,
                    "Usage: /analyze &lt;jobId&gt;");
            case "/documents" -> documents(args);
            case "/resumes" -> jobOnly(args, TelegramCommand.Kind.RESUMES,
                    "Usage: /resumes &lt;jobId&gt;");
            case "/covernotes" -> jobOnly(args, TelegramCommand.Kind.COVER_NOTES,
                    "Usage: /covernotes &lt;jobId&gt;");
            case "/selectdocs" -> selectDocuments(args);
            case "/interview" -> interview(args);
            case "/rejected" -> rejected(args);
            case "/followup" -> followup(args);
            case "/note" -> note(args);
            case "/applications" -> applications(args);
            default -> TelegramCommandParseResult.success(TelegramCommand.simple(TelegramCommand.Kind.HELP));
        };
    }

    private TelegramCommandParseResult documents(String args) {
        String[] parts = args.isEmpty() ? new String[0] : args.split("\\s+");
        Long jobId = parts.length >= 1 && parts.length <= 3 ? parseId(parts[0]) : null;
        if (jobId == null) return TelegramCommandParseResult.failure(
                "Usage: /documents &lt;jobId&gt; [resume|all] [docx|pdf|both]");
        TelegramCommand.DocumentScope scope = TelegramCommand.DocumentScope.ALL;
        if (parts.length >= 2) {
            scope = switch (parts[1].toLowerCase(Locale.ROOT)) {
                case "resume" -> TelegramCommand.DocumentScope.RESUME;
                case "all" -> TelegramCommand.DocumentScope.ALL;
                default -> null;
            };
        }
        Set<DocumentFormat> formats = Set.of(DocumentFormat.DOCX, DocumentFormat.PDF);
        if (parts.length == 3) {
            formats = switch (parts[2].toLowerCase(Locale.ROOT)) {
                case "docx" -> Set.of(DocumentFormat.DOCX);
                case "pdf" -> Set.of(DocumentFormat.PDF);
                case "both" -> Set.of(DocumentFormat.DOCX, DocumentFormat.PDF);
                default -> null;
            };
        }
        if (scope == null || formats == null) return TelegramCommandParseResult.failure(
                "Usage: /documents &lt;jobId&gt; [resume|all] [docx|pdf|both]");
        return TelegramCommandParseResult.success(new TelegramCommand(
                TelegramCommand.Kind.DOCUMENTS, jobId, null, null, scope, formats,
                null, null, null, null));
    }

    private TelegramCommandParseResult selectDocuments(String args) {
        String[] parts = args.isEmpty() ? new String[0] : args.split("\\s+");
        if (parts.length < 2 || parts.length > 3) return TelegramCommandParseResult.failure(
                "Usage: /selectdocs &lt;jobId&gt; &lt;resumeVersionId&gt; [coverNoteId|none]");
        Long jobId = parseId(parts[0]);
        Long resumeId = parseId(parts[1]);
        Long coverId = null;
        if (parts.length == 3 && !parts[2].equalsIgnoreCase("none")) {
            coverId = parseId(parts[2]);
            if (coverId == null) return TelegramCommandParseResult.failure(
                    "Usage: /selectdocs &lt;jobId&gt; &lt;resumeVersionId&gt; [coverNoteId|none]");
        }
        if (jobId == null || resumeId == null) return TelegramCommandParseResult.failure(
                "Usage: /selectdocs &lt;jobId&gt; &lt;resumeVersionId&gt; [coverNoteId|none]");
        return TelegramCommandParseResult.success(new TelegramCommand(
                TelegramCommand.Kind.SELECT_DOCUMENTS, jobId, resumeId, coverId,
                null, Set.of(), null, null, null, null));
    }

    private TelegramCommandParseResult noArgs(String args, TelegramCommand.Kind kind, String usage) {
        return args.isEmpty() ? TelegramCommandParseResult.success(TelegramCommand.simple(kind))
                : TelegramCommandParseResult.failure(usage);
    }

    private TelegramCommandParseResult requiredText(String args, TelegramCommand.Kind kind, String usage) {
        return args.isEmpty() ? TelegramCommandParseResult.failure(usage)
                : TelegramCommandParseResult.success(
                        new TelegramCommand(kind, null, args, null, null, null));
    }

    private TelegramCommandParseResult jobOnly(String args, TelegramCommand.Kind kind, String usage) {
        Long id = parseId(args);
        return id == null ? TelegramCommandParseResult.failure(usage)
                : TelegramCommandParseResult.success(
                        new TelegramCommand(kind, id, null, null, null, null));
    }

    private TelegramCommandParseResult interview(String args) {
        String[] parts = args.split("\\s+", 2);
        Long id = parts.length == 2 ? parseId(parts[0]) : null;
        if (id == null) return TelegramCommandParseResult.failure(
                "Usage: /interview &lt;jobId&gt; &lt;ISO datetime with offset&gt;");
        try {
            return TelegramCommandParseResult.success(new TelegramCommand(
                    TelegramCommand.Kind.INTERVIEW, id, null,
                    OffsetDateTime.parse(parts[1]).toInstant(), null, null));
        } catch (DateTimeParseException invalid) {
            return TelegramCommandParseResult.failure(
                    "Use an ISO-8601 interview datetime with an explicit UTC offset.");
        }
    }

    private TelegramCommandParseResult rejected(String args) {
        String[] parts = args.split("\\s+", 2);
        Long id = parts.length > 0 ? parseId(parts[0]) : null;
        if (id == null) return TelegramCommandParseResult.failure(
                "Usage: /rejected &lt;jobId&gt; [reason]");
        String reason = parts.length == 2 ? parts[1] : null;
        if (reason != null && reason.length() > ApplicationTrackerService.MAX_REJECTION_REASON_LENGTH) {
            return TelegramCommandParseResult.failure("Rejection reason is too long.");
        }
        return TelegramCommandParseResult.success(new TelegramCommand(
                TelegramCommand.Kind.REJECTED, id, reason, null, null, null));
    }

    private TelegramCommandParseResult followup(String args) {
        String[] parts = args.split("\\s+", 2);
        Long id = parts.length == 2 ? parseId(parts[0]) : null;
        if (id == null) return TelegramCommandParseResult.failure(
                "Usage: /followup &lt;jobId&gt; &lt;YYYY-MM-DD|clear&gt;");
        if (parts[1].equalsIgnoreCase("clear")) {
            return TelegramCommandParseResult.success(new TelegramCommand(
                    TelegramCommand.Kind.FOLLOWUP, id, null, null, null, null));
        }
        try {
            return TelegramCommandParseResult.success(new TelegramCommand(
                    TelegramCommand.Kind.FOLLOWUP, id, null, null, LocalDate.parse(parts[1]), null));
        } catch (DateTimeParseException invalid) {
            return TelegramCommandParseResult.failure("Use a follow-up date as YYYY-MM-DD or clear.");
        }
    }

    private TelegramCommandParseResult note(String args) {
        String[] parts = args.split("\\s+", 2);
        Long id = parts.length == 2 ? parseId(parts[0]) : null;
        if (id == null) return TelegramCommandParseResult.failure(
                "Usage: /note &lt;jobId&gt; &lt;text|clear&gt;");
        String note = parts[1].equalsIgnoreCase("clear") ? null : parts[1];
        if (note != null && note.length() > ApplicationTrackerService.MAX_NOTES_LENGTH) {
            return TelegramCommandParseResult.failure("Note is too long.");
        }
        return TelegramCommandParseResult.success(new TelegramCommand(
                TelegramCommand.Kind.NOTE, id, note, null, null, null));
    }

    private TelegramCommandParseResult applications(String args) {
        if (args.isEmpty()) {
            return TelegramCommandParseResult.success(
                    TelegramCommand.simple(TelegramCommand.Kind.APPLICATIONS));
        }
        try {
            ApplicationStatus filter = ApplicationStatus.valueOf(args.toUpperCase(Locale.ROOT));
            return TelegramCommandParseResult.success(new TelegramCommand(
                    TelegramCommand.Kind.APPLICATIONS, null, null, null, null, filter));
        } catch (IllegalArgumentException invalid) {
            return TelegramCommandParseResult.failure(
                    "Usage: /applications [SAVED|APPLIED|INTERVIEW|REJECTED|OFFER|WITHDRAWN]");
        }
    }

    private Long parseId(String value) {
        if (value == null || !value.matches("[1-9]\\d{0,18}")) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    private int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isWhitespace(value.charAt(i))) return i;
        return -1;
    }
}
