package com.jobpilot.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCard;
import com.jobpilot.support.TestProperties;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.util.ReflectionTestUtils;

class TelegramBotNotifierTest {
    @Test
    void sendsExcellentMatchWithOpenVacancySaveAndAppliedButtons() {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        var properties = TestProperties.create(commandsEnabledTelegram());
        var notifier = new TelegramBotNotifier(http, properties);

        Job job = job();
        ReflectionTestUtils.setField(job, "id", 42L);
        notifier.notifyExcellent(job, score());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(http).postJson(eq("https://api.telegram.org/botsecret-token/sendMessage"), body.capture());
        assertThat(body.getValue()).containsEntry("chat_id", "-100123");
        assertThat(body.getValue().get("text").toString()).contains("87/100", "Java &lt;Intern&gt;");
        assertThat(body.getValue()).containsKey("reply_markup");
        assertThat(body.getValue().get("reply_markup").toString())
                .contains("Open vacancy", "app:save:42", "app:applied:42")
                .doesNotContain(job.getCanonicalUrl() + "app:");
    }

    @Test
    void sendsOnlyOpenVacancyButtonWhenCommandsAreDisabled() {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        var properties = TestProperties.create(new JobPilotProperties.Telegram("secret-token", "-100123"));
        var notifier = new TelegramBotNotifier(http, properties);
        Job job = job();
        ReflectionTestUtils.setField(job, "id", 42L);

        notifier.notifyExcellent(job, score());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(http).postJson(any(), body.capture());
        assertThat(body.getValue().get("reply_markup").toString())
                .contains("Open vacancy")
                .doesNotContain("callback_data", "app:save:42", "app:applied:42");
    }

    @Test
    void isNoOpWithoutTokenAndChannel() {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        var notifier = new TelegramBotNotifier(http, TestProperties.create());
        notifier.notifyExcellent(job(), score());
        verify(http, never()).postJson(any(), any());
    }

    @Test
    void splitsLongDigestIntoMessagesUnderTelegramLimit() {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        var properties = TestProperties.create(new JobPilotProperties.Telegram("secret-token", "-100123"));
        var notifier = new TelegramBotNotifier(http, properties);
        List<JobScore> digest = new ArrayList<>();
        Instant now = Instant.parse("2026-07-17T12:00:00Z");
        for (int i = 0; i < 20; i++) {
            String title = ("Java Backend Developer Internship " + i + " — Bucharest Office — ").repeat(5);
            Job job = new Job("greenhouse", "job-" + i, "https://example.com/jobs/" + i, title,
                    "Example Company " + i, "Bucharest, Romania", RemoteType.HYBRID, "Internship",
                    "Java role", now, null, "a".repeat(64), "b".repeat(64), "c".repeat(64), now);
            digest.add(new JobScore(job, score(), now));
        }

        notifier.sendGoodMatchDigest(digest);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(http, org.mockito.Mockito.atLeast(2)).postJson(any(), body.capture());
        int listedJobs = 0;
        for (Map<String, Object> sent : body.getAllValues()) {
            String text = sent.get("text").toString();
            assertThat(text.length()).isLessThanOrEqualTo(TelegramBotNotifier.TELEGRAM_MESSAGE_LIMIT);
            assertThat(text).startsWith("🟡");
            listedJobs += text.split("/100 — ", -1).length - 1;
        }
        assertThat(listedJobs).isEqualTo(20);
    }

    @Test
    void neverExposesTheBotTokenWhenTheApiCallFails() {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        var properties = TestProperties.create(new JobPilotProperties.Telegram("secret-token", "-100123"));
        var notifier = new TelegramBotNotifier(http, properties);
        when(http.postJson(any(), any())).thenThrow(new ResourceAccessException(
                "I/O error on POST request for \"https://api.telegram.org/botsecret-token/sendMessage\""));

        Throwable failure = catchThrowable(() -> notifier.notifyExcellent(job(), score()));

        assertThat(failure).isInstanceOf(IllegalStateException.class);
        assertThat(failure.getMessage()).doesNotContain("secret-token");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.toString()).doesNotContain("secret-token");
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

    private JobPilotProperties.Telegram commandsEnabledTelegram() {
        return new JobPilotProperties.Telegram(
                "secret-token", "-100123", "JobPilotBot", true, "-100123", "777",
                Duration.ofSeconds(25), Duration.ofSeconds(2), 50, 3, true);
    }
}
