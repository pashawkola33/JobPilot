package com.jobpilot.telegram;

import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.matching.ScoreCard;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class TelegramBotNotifier implements TelegramNotifier {
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
        StringBuilder message = new StringBuilder("🟡 <b>Daily good matches</b>\n\n");
        for (JobScore score : jobScores) {
            Job job = score.getJob();
            message.append("<b>").append(score.getScore()).append("/100 — ")
                    .append(escape(job.getTitle())).append("</b>\n")
                    .append(escape(job.getCompany())).append(" · ")
                    .append(escape(String.valueOf(job.getLocation()))).append("\n")
                    .append("<a href=\"").append(escape(job.getCanonicalUrl())).append("\">Open vacancy</a>\n\n");
        }
        send(message.toString(), List.of());
    }

    private void send(String text, List<List<Map<String, String>>> buttons) {
        Map<String, Object> body = buttons.isEmpty()
                ? Map.of("chat_id", telegram.channelId(), "text", text, "parse_mode", "HTML",
                        "disable_web_page_preview", true)
                : Map.of("chat_id", telegram.channelId(), "text", text, "parse_mode", "HTML",
                        "disable_web_page_preview", true,
                        "reply_markup", Map.of("inline_keyboard", buttons));
        http.postJson("https://api.telegram.org/bot" + telegram.botToken() + "/sendMessage", body);
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
