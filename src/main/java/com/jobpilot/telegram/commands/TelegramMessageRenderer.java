package com.jobpilot.telegram.commands;

import com.jobpilot.common.Utf16;

import com.jobpilot.applications.application.ApplicationMutationResult;
import com.jobpilot.applications.application.ApplicationView;
import com.jobpilot.applications.application.ApplicationHistoryView;
import com.jobpilot.llm.domain.JobAnalysisResult;
import com.jobpilot.manualurl.domain.ManualJobSubmissionResult;
import com.jobpilot.resume.application.ApplicationDocumentSelectionResult;
import com.jobpilot.resume.application.CoverNoteMetadataView;
import com.jobpilot.resume.application.DocumentGenerationResult;
import com.jobpilot.resume.application.ResumeMetadataView;
import com.jobpilot.jobreview.application.JobDetailView;
import com.jobpilot.jobreview.application.JobQueueItem;
import com.jobpilot.jobreview.application.JobQueuePage;
import com.jobpilot.jobreview.application.JobReviewStats;
import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.telegram.TelegramBotNotifier;
import com.jobpilot.telegram.review.TelegramCallbackData;
import java.net.URI;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            /history &lt;jobId&gt;
            /applications [status]
            /analyze &lt;jobId&gt;
            /documents &lt;jobId&gt; [resume|all] [docx|pdf|both]
            /resumes &lt;jobId&gt;
            /covernotes &lt;jobId&gt;
            /selectdocs &lt;jobId&gt; &lt;resumeVersionId&gt; [coverNoteId|none]
            """.strip();

    private static final String REVIEW_HELP = """
            <b>Review queue</b>
            /matches — MATCH vacancies awaiting triage
            /review — REVIEW vacancies awaiting triage
            /saved — vacancies you saved
            /applied — vacancies you marked as applied
            /stats — queue counts
            /job &lt;id&gt; — one vacancy with actions
            /note &lt;id&gt; &lt;text|clear&gt; — attach a note (also saves an untriaged vacancy)
            /reset &lt;id&gt; — back to unreviewed
            """.strip();

    public String help() {
        return REVIEW_HELP + "\n\n" + HELP;
    }

    public String start() {
        return "👋 <b>JobPilot review bot</b>\n\n"
                + "Triage screened vacancies here. Send /matches to begin, "
                + "/stats for queue counts, or /help for every command.";
    }

    public String error(String safeMessage) {
        return "⚠️ " + escape(limit(safeMessage, 500));
    }

    public String progress(TelegramCommand.Kind kind) {
        return kind == TelegramCommand.Kind.ANALYZE
                ? "⏳ Analysis requested. Existing completed analysis will be reused."
                : "⏳ Document generation requested. Existing valid artifacts will be reused.";
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

    public String analysis(JobAnalysisResult result) {
        StringBuilder text = new StringBuilder("<b>Job analysis</b>\n");
        appendComplete(text, "Job ID: " + result.jobId() + "\n", TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        appendComplete(text, "Status: <b>" + result.status() + "</b>\n",
                TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        if (result.analysisId() != null) appendComplete(text, "Analysis ID: " + result.analysisId() + "\n",
                TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        if (result.failureCategory() != null) appendComplete(text,
                "Safe fallback category: " + result.failureCategory() + "\n",
                TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        if (result.analysis() != null) {
            append(text, "Summary", limit(result.analysis().roleSummary(), 500),
                    TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
            appendList(text, "Must have", result.analysis().mustHaveRequirements());
            appendList(text, "Candidate gaps", result.analysis().candidateGaps());
            appendComplete(text, "\nConfidence: " + result.analysis().confidenceScore() + "/100",
                    TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        }
        return text.toString().strip();
    }

    public String documents(DocumentGenerationResult result) {
        StringBuilder text = new StringBuilder("<b>Application documents</b>\n");
        appendComplete(text, "Job ID: " + result.jobId() + "\nStatus: <b>" + result.status()
                + "</b>\n", TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        if (result.resumeVersionId() != null) {
            long id = result.resumeVersionId();
            appendComplete(text, "Resume version ID: " + id + "\nMetadata: /internal/v1/resumes/"
                    + id + "\n", TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        }
        if (result.coverNoteId() != null) {
            long id = result.coverNoteId();
            appendComplete(text, "Cover note ID: " + id + "\nMetadata: /internal/v1/cover-notes/"
                    + id + "\n", TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        }
        if (result.fallbackUsed() && result.fallbackReason() != null) {
            appendComplete(text, "Safe fallback category: " + result.fallbackReason() + "\n",
                    TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        }
        if (result.resumePreview() != null) {
            appendComplete(text, "\n<b>Bounded preview</b>\n"
                    + escape(limit(result.resumePreview(), 800)), TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        }
        return text.toString().strip();
    }

    public String resumes(List<ResumeMetadataView> values) {
        if (values.isEmpty()) return "No résumé versions exist for that job.";
        StringBuilder text = new StringBuilder("<b>Résumé versions</b>\n\n");
        for (ResumeMetadataView value : values) {
            String entry = "<b>#" + value.id() + " · " + value.renderStatus() + "</b>\n"
                    + "Formats: " + formats(value.docxAvailable(), value.pdfAvailable()) + "\n"
                    + "Metadata: /internal/v1/resumes/" + value.id() + "\n"
                    + downloadRoutes("resumes", value.id(), value.docxAvailable(), value.pdfAvailable())
                    + "\n";
            appendComplete(text, entry, TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        }
        return text.toString().strip();
    }

    public String coverNotes(List<CoverNoteMetadataView> values) {
        if (values.isEmpty()) return "No cover notes exist for that job.";
        StringBuilder text = new StringBuilder("<b>Cover notes</b>\n\n");
        for (CoverNoteMetadataView value : values) {
            String entry = "<b>#" + value.id() + " · " + value.renderStatus() + "</b>\n"
                    + "Resume version ID: " + value.resumeVersionId() + "\n"
                    + "Formats: " + formats(value.docxAvailable(), value.pdfAvailable()) + "\n"
                    + "Metadata: /internal/v1/cover-notes/" + value.id() + "\n"
                    + downloadRoutes("cover-notes", value.id(), value.docxAvailable(), value.pdfAvailable())
                    + "\n";
            appendComplete(text, entry, TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        }
        return text.toString().strip();
    }

    public String selection(ApplicationDocumentSelectionResult result) {
        String prefix = result.changed() ? "✅ Documents selected.\n" : "ℹ️ Documents already selected.\n";
        return prefix + "Job ID: " + result.jobId() + "\nResume version ID: "
                + result.resumeVersionId() + "\nCover note ID: "
                + (result.coverNoteId() == null ? "none" : result.coverNoteId())
                + "\nApplication status unchanged: <b>" + result.applicationStatus() + "</b>";
    }

    public String history(List<ApplicationHistoryView> values) {
        if (values.isEmpty()) return "No application history exists for that job.";
        StringBuilder text = new StringBuilder("<b>Application history</b>\n\n");
        for (ApplicationHistoryView value : values.stream().limit(50).toList()) {
            String entry = value.changedAt() + " · "
                    + (value.previousStatus() == null ? "NEW" : value.previousStatus())
                    + " → <b>" + value.newStatus() + "</b> · " + value.source() + "\n";
            appendComplete(text, entry, TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
        }
        return text.toString().strip();
    }

    // ---------------------------------------------------------------- review queue

    public String queue(JobQueuePage page) {
        if (page.items().isEmpty()) {
            return "Nothing in <b>" + page.queue().name() + "</b>. Try /stats.";
        }
        StringBuilder text = new StringBuilder("<b>" + page.queue().name() + "</b> — "
                + page.total() + " total · page " + (page.page() + 1) + "\n\n");
        for (JobQueueItem item : page.items()) {
            String entry = "<b>" + item.score() + "/100 · #" + item.id() + "</b> "
                    + statusBadge(item.workflowStatus()) + "\n"
                    + "<b>" + escape(limit(item.title(), 90)) + "</b>\n"
                    + escape(limit(item.company(), 60)) + " · "
                    + escape(limit(item.location(), 50)) + "\n"
                    + link(item.canonicalUrl()) + "\n\n";
            if (text.length() + entry.length() > TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT) break;
            text.append(entry);
        }
        return text.toString().strip();
    }

    public String job(JobDetailView job) {
        StringBuilder text = new StringBuilder();
        int max = TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT;
        appendComplete(text, "<b>" + escape(limit(job.title(), 90)) + "</b>\n", max);
        appendComplete(text, escape(limit(job.company(), 60)) + " · "
                + escape(limit(job.location(), 50)) + "\n", max);
        appendComplete(text, "Job ID: " + job.id() + " · " + job.disposition()
                + " · " + job.score() + "/100\n", max);
        appendComplete(text, "Status: <b>" + job.workflowStatus() + "</b>\n", max);
        append(text, "Source", limit(job.source(), 40), max);
        append(text, "Tenant", limit(job.tenant(), 60), max);
        append(text, "Note", limit(job.note(), 300), max);
        append(text, "Eligibility", limit(job.eligibilityReason(), 200), max);
        append(text, "Early career", limit(job.earlyCareerEligibilityReason(), 200), max);
        if (!job.reasons().isEmpty()) {
            appendComplete(text, "\n<b>Screening:</b>", max);
            job.reasons().stream().limit(5).forEach(reason -> appendComplete(text,
                    "\n• " + escape(limit(reason.stage() + " " + reason.code() + " "
                            + reason.message(), 120)), max));
            appendComplete(text, "\n", max);
        }
        appendComplete(text, "\n" + link(job.canonicalUrl()), max);
        return text.toString().strip();
    }

    public String stats(JobReviewStats stats) {
        return "<b>Review queue</b>\n\n"
                + "Unreviewed MATCH: " + stats.unreviewedMatch() + "\n"
                + "Unreviewed REVIEW: " + stats.unreviewedReview() + "\n"
                + "Saved: " + stats.saved() + "\n"
                + "Applied: " + stats.applied() + "\n"
                + "Dismissed: " + stats.dismissed();
    }

    public String workflow(WorkflowView view) {
        String verb = switch (view.status()) {
            case UNREVIEWED -> "reset to unreviewed";
            case SAVED -> "saved";
            case APPLIED -> "marked as applied";
            case DISMISSED -> "dismissed";
        };
        String prefix = view.changed() ? "✅ Vacancy #" : "ℹ️ Already · vacancy #";
        String note = view.note() == null ? ""
                : "\nNote: " + escape(limit(view.note(), 300));
        return prefix + view.jobId() + " " + verb + "." + note;
    }

    public String matchCard(JobQueueItem item) {
        return "🟢 <b>New MATCH · " + item.score() + "/100</b>\n\n"
                + "<b>" + escape(limit(item.title(), 90)) + "</b>\n"
                + escape(limit(item.company(), 60)) + " · "
                + escape(limit(item.location(), 50)) + "\n"
                + "Job ID: " + item.id() + "\n\n"
                + link(item.canonicalUrl());
    }

    public String matchSummary(long remaining) {
        return "🟢 <b>" + remaining + " further new MATCH "
                + (remaining == 1 ? "vacancy" : "vacancies") + "</b>\n\n"
                + "Send /matches to review them.";
    }

    public String reviewDigest(long total, List<JobQueueItem> items) {
        StringBuilder text = new StringBuilder("🟡 <b>" + total + " new REVIEW "
                + (total == 1 ? "vacancy" : "vacancies") + "</b>\n\n");
        for (JobQueueItem item : items) {
            String entry = "• <b>" + item.score() + "/100 · #" + item.id() + "</b> "
                    + escape(limit(item.title(), 70)) + " — "
                    + escape(limit(item.company(), 40)) + "\n";
            if (text.length() + entry.length() > TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT - 40) break;
            text.append(entry);
        }
        return text.append("\nSend /review to triage them.").toString();
    }

    /** Inline keyboard for one vacancy; the Open row appears only for a safe HTTPS URL. */
    public List<List<Map<String, String>>> jobButtons(long jobId, String canonicalUrl) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        String url = safeUrl(canonicalUrl);
        if (url != null) rows.add(List.of(Map.of("text", "Open vacancy", "url", url)));
        rows.add(List.of(
                Map.of("text", "Save", "callback_data",
                        TelegramCallbackData.action(TelegramCallbackData.Action.SAVE, jobId)),
                Map.of("text", "Applied", "callback_data",
                        TelegramCallbackData.action(TelegramCallbackData.Action.APPLIED, jobId))));
        rows.add(List.of(
                Map.of("text", "Dismiss", "callback_data",
                        TelegramCallbackData.action(TelegramCallbackData.Action.DISMISS, jobId)),
                Map.of("text", "Reset", "callback_data",
                        TelegramCallbackData.action(TelegramCallbackData.Action.RESET, jobId))));
        return rows;
    }

    /** Queue keyboard: only a Next button, and only when another page exists. */
    public List<List<Map<String, String>>> queueButtons(JobQueuePage page) {
        if (!page.hasNext()) return List.of();
        return List.of(List.of(Map.of("text", "Next", "callback_data",
                TelegramCallbackData.next(page.queue(), page.page() + 1))));
    }

    /**
     * Canonical HTTPS only. A vacancy stored with any other scheme is dropped rather than
     * rendered, so a provider can never place a javascript: or data: target in a message.
     */
    public static String safeUrl(String value) {
        if (value == null || value.length() > 512) return null;
        String candidate = value.strip();
        if (!candidate.regionMatches(true, 0, "https://", 0, 8)) return null;
        for (int i = 0; i < candidate.length(); i++) {
            char character = candidate.charAt(i);
            if (character <= ' ' || character == '"' || character == '<' || character == '>') return null;
        }
        try {
            URI parsed = URI.create(candidate);
            return parsed.getHost() == null || parsed.getHost().isBlank() ? null : candidate;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    private String link(String canonicalUrl) {
        String url = safeUrl(canonicalUrl);
        return url == null ? "<i>No public link available.</i>"
                : "<a href=\"" + escape(url) + "\">Open vacancy</a>";
    }

    private String statusBadge(WorkflowStatus status) {
        return switch (status) {
            case UNREVIEWED -> "";
            case SAVED -> "· ⭐ saved";
            case APPLIED -> "· ✉️ applied";
            case DISMISSED -> "· 🚫 dismissed";
        };
    }

    private String formats(boolean docx, boolean pdf) {
        if (docx && pdf) return "DOCX, PDF";
        if (docx) return "DOCX";
        if (pdf) return "PDF";
        return "none";
    }

    private String downloadRoutes(String kind, long id, boolean docx, boolean pdf) {
        StringBuilder routes = new StringBuilder();
        if (docx) routes.append("DOCX: /internal/v1/").append(kind).append('/').append(id)
                .append("/docx\n");
        if (pdf) routes.append("PDF: /internal/v1/").append(kind).append('/').append(id)
                .append("/pdf\n");
        return routes.toString();
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
        return value.length() <= max ? value : Utf16.truncate(value, max - 1) + "…";
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
