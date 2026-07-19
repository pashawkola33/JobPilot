package com.jobpilot.telegram.commands;

import com.jobpilot.applications.application.ApplicationMutationResult;
import com.jobpilot.applications.application.ApplicationView;
import com.jobpilot.manualurl.domain.ManualJobSubmissionResult;
import com.jobpilot.telegram.TelegramBotNotifier;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TelegramMessageRenderer {
    private static final String HELP = """
            <b>JobPilot commands</b>
            /add &lt;public vacancy URL&gt;
            /save &lt;jobId&gt;
            /applied &lt;jobId&gt;
            /interview &lt;jobId&gt; &lt;ISO datetime with offset&gt;
            /rejected &lt;jobId&gt; [reason]
            /offer &lt;jobId&gt;
            /withdraw &lt;jobId&gt;
            /followup &lt;jobId&gt; &lt;YYYY-MM-DD|clear&gt;
            /note &lt;jobId&gt; &lt;text|clear&gt;
            /status &lt;jobId&gt;
            /applications [status]
            """.strip();

    public String help() {
        return HELP;
    }

    public String error(String safeMessage) {
        return "⚠️ " + escape(limit(safeMessage, 500));
    }

    public String mutation(ApplicationMutationResult result) {
        String prefix = result.changed() ? "✅ Application updated.\n\n" : "ℹ️ Already up to date.\n\n";
        return prefix + application(result.application(),
                TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT - prefix.length());
    }

    public String application(ApplicationView value) {
        return application(value, TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
    }

    private String application(ApplicationView value, int messageLimit) {
        StringBuilder text = new StringBuilder();
        appendComplete(text, "<b>" + escape(limit(value.title(), 80)) + "</b>\n", messageLimit);
        appendComplete(text, escape(limit(value.company(), 60)) + "\n", messageLimit);
        appendComplete(text, "Job ID: " + value.jobId() + "\n", messageLimit);
        appendComplete(text, "Status: <b>" + value.status() + "</b>\n", messageLimit);
        append(text, "Applied", value.applicationDate() == null ? null
                : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.applicationDate().atOffset(ZoneOffset.UTC)),
                messageLimit);
        append(text, "Interview", value.interviewDate() == null ? null
                : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.interviewDate().atOffset(ZoneOffset.UTC)),
                messageLimit);
        append(text, "Follow-up", value.nextFollowUpDate(), messageLimit);
        append(text, "Notes", limit(value.notes(), 100), messageLimit);
        append(text, "Rejection reason", limit(value.rejectionReason(), 80), messageLimit);
        if (value.canonicalUrl() != null) {
            appendComplete(text, "<a href=\"" + escape(limit(value.canonicalUrl(), 150))
                    + "\">Open vacancy</a>", messageLimit);
        }
        return text.toString().strip();
    }

    public String applications(List<ApplicationView> values) {
        if (values.isEmpty()) return "No tracked applications.";
        StringBuilder text = new StringBuilder("<b>Applications</b>\n\n");
        for (ApplicationView value : values.stream().limit(20).toList()) {
            String entry = "<b>" + value.status() + " · #" + value.jobId() + "</b> — "
                    + escape(limit(value.title(), 120)) + " · "
                    + escape(limit(value.company(), 100)) + "\n"
                    + "<a href=\"" + escape(limit(value.canonicalUrl(), 500)) + "\">Open vacancy</a>\n\n";
            if (text.length() + entry.length() > TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT) break;
            text.append(entry);
        }
        return text.toString().strip();
    }

    public String manual(ManualJobSubmissionResult result) {
        StringBuilder text = new StringBuilder();
        int messageLimit = TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT;
        appendComplete(text, "<b>" + result.status() + "</b> — "
                + escape(limit(result.message(), 80)), messageLimit);
        if (result.jobId() != null) appendComplete(text, "\nJob ID: " + result.jobId(), messageLimit);
        if (result.score() != null) appendComplete(text, "\nScore: " + result.score() + "/100", messageLimit);
        appendList(text, "Strengths", result.strengths());
        appendList(text, "Risks", result.risks());
        if (result.canonicalUrl() != null) {
            appendComplete(text, "\n<a href=\"" + escape(limit(result.canonicalUrl(), 150))
                    + "\">Open vacancy</a>", messageLimit);
        }
        return text.toString();
    }

    private void appendList(StringBuilder text, String label, List<String> values) {
        if (values == null || values.isEmpty()) return;
        appendComplete(text, "\n<b>" + label + ":</b>", TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        values.stream().limit(3).forEach(value -> appendComplete(text,
                "\n• " + escape(limit(value, 40)), TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT));
    }

    private void append(StringBuilder text, String label, Object value, int messageLimit) {
        if (value != null && !value.toString().isBlank()) {
            appendComplete(text, label + ": " + escape(value.toString()) + "\n", messageLimit);
        }
    }

    private void appendComplete(StringBuilder text, String section, int messageLimit) {
        if (text.length() + section.length() <= messageLimit) text.append(section);
    }

    private String limit(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
