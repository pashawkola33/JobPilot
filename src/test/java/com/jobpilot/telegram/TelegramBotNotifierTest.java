package com.jobpilot.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCard;
import com.jobpilot.support.TestProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TelegramBotNotifierTest {
    @Test
    void sendsExcellentMatchWithOpenVacancyButton() {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        var properties = TestProperties.create(new JobPilotProperties.Telegram("secret-token", "-100123"));
        var notifier = new TelegramBotNotifier(http, properties);

        notifier.notifyExcellent(job(), score());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(http).postJson(eq("https://api.telegram.org/botsecret-token/sendMessage"), body.capture());
        assertThat(body.getValue()).containsEntry("chat_id", "-100123");
        assertThat(body.getValue().get("text").toString()).contains("87/100", "Java &lt;Intern&gt;");
        assertThat(body.getValue()).containsKey("reply_markup");
    }

    @Test
    void isNoOpWithoutTokenAndChannel() {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        var notifier = new TelegramBotNotifier(http, TestProperties.create());
        notifier.notifyExcellent(job(), score());
        verify(http, never()).postJson(any(), any());
    }

    private Job job() {
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        return new Job("greenhouse", "1", "https://example.com/jobs/1", "Java <Intern>",
                "Example", "Bucharest", RemoteType.HYBRID, "Internship", "Java role", now,
                null, "a".repeat(64), "b".repeat(64), "c".repeat(64), now);
    }

    private ScoreCard score() {
        return new ScoreCard(87, ScoreBand.EXCELLENT_MATCH, true, 25, 22, 15, 8, 10, 10,
                5, 8, List.of("Java and Spring Boot match"), List.of("Docker is preferred"), List.of());
    }
}
