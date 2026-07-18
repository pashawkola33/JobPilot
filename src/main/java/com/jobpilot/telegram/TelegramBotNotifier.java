package com.jobpilot.telegram;

import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.matching.ScoreCard;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.HtmlUtils;

@Component
public class TelegramBotNotifier implements TelegramNotifier {
    static final int TELEGRAM_MESSAGE_LIMIT = 4096;
    private static final String DIGEST_HEADER = "🟡 <b>Daily good matches</b>\n\n";
    private final ExternalHttpClient http;
    private final JobPilotProperties.Telegram telegram;

    public TelegramBotNotifier(ExternalHttpClient http, JobPilotProperties properties) {
        this.http = http;
        this.telegram = properties.telegram();
    }

    @Override
    public void notifyExcellent(Job job, ScoreCard score) {
        if (!enabled()) return;
        String text = "🟢 <b>MATCH: " + score.score() + "/100</b>\n\n"
                + "<b>" + escape(job.getTitle()) + "</b>\n" + escape(job.getCompany()) + "\n"
                + "📍 " + escape(String.valueOf(job.getLocation())) + " — " + job.getRemoteType() + "\n\n"
                + "<b>Why it fits:</b>\n" + lines("✅ ", score.strengths())
                + (score.risks().isEmpty() ? "" : "\n<b>Risks:</b>\n" + lines("⚠️ ", score.risks()));
        send(text, List.of(List.of(Map.of("text", "Open vacancy", "url", job.getCanonicalUrl()))));
    }

    @Override
    public void sendGoodMatchDigest(List<JobScore> jobScores) {
        if (!enabled() || jobScores.isEmpty()) return;
        StringBuilder message = new StringBuilder(DIGEST_HEADER);
        for (JobScore score : jobScores) {
            String entry = digestEntry(score);
            if (message.length() + entry.length() > TELEGRAM_MESSAGE_LIMIT) {
                send(message.toString(), List.of());
                message = new StringBuilder(DIGEST_HEADER);
            }
            message.append(entry);
        }
        send(message.toString(), List.of());
    }

    private String digestEntry(JobScore score) {
        Job job = score.getJob();
        return "<b>" + score.getScore() + "/100 — " + escape(job.getTitle()) + "</b>\n"
                + escape(job.getCompany()) + " · " + escape(String.valueOf(job.getLocation())) + "\n"
                + "<a href=\"" + escape(job.getCanonicalUrl()) + "\">Open vacancy</a>\n\n";
    }

    private void send(String text, List<List<Map<String, String>>> buttons) {
        Map<String, Object> body = buttons.isEmpty()
                ? Map.of("chat_id", telegram.channelId(), "text", text, "parse_mode", "HTML",
                        "disable_web_page_preview", true)
                : Map.of("chat_id", telegram.channelId(), "text", text, "parse_mode", "HTML",
                        "disable_web_page_preview", true,
                        "reply_markup", Map.of("inline_keyboard", buttons));
        try {
            http.postJson("https://api.telegram.org/bot" + telegram.botToken() + "/sendMessage", body);
        } catch (RestClientException exception) {
            // Network exceptions embed the request URL, which contains the bot token.
            // Rethrow without the original message or cause so the token can never reach a log.
            throw new IllegalStateException(
                    "Telegram sendMessage failed: " + exception.getClass().getSimpleName());
        }
    }

    private boolean enabled() {
        return telegram.enabled() && telegram.channelId() != null && !telegram.channelId().isBlank();
    }

    private String lines(String prefix, List<String> values) {
        return values.stream().limit(5).map(value -> prefix + escape(value))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(String.valueOf(value));
    }
}
