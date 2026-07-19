package com.jobpilot.telegram.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.support.TestProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.ResourceAccessException;

class TelegramApiClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sendsBoundedLongPollRequestAndParsesTypedUpdates() throws Exception {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        when(http.postJson(contains("/getUpdates"), any())).thenReturn(json.readTree("""
                {"ok":true,"result":[
                  {"update_id":9,"message":{"message_id":2,"from":{"id":777},
                    "chat":{"id":-100555},"text":"/save 3"}},
                  {"update_id":10,"callback_query":{"id":"cb-1","from":{"id":777},
                    "message":{"message_id":2,"chat":{"id":-100555}},"data":"app:save:3"}}
                ]}
                """));
        var client = new TelegramApiClient(http, properties());

        var updates = client.getUpdates(9L, Duration.ofSeconds(25), 50);

        assertThat(updates).hasSize(2);
        assertThat(updates.getFirst().message().text()).isEqualTo("/save 3");
        assertThat(updates.getLast().callbackQuery().data()).isEqualTo("app:save:3");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(http).postJson(contains("/getUpdates"), body.capture());
        assertThat(body.getValue()).containsEntry("offset", 9L).containsEntry("timeout", 25L)
                .containsEntry("limit", 50);
        assertThat(body.getValue().get("allowed_updates").toString())
                .contains("message", "callback_query");
    }

    @Test
    void sanitizesTransportFailuresWithoutTokenUrlCauseOrUpdateText() {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        when(http.postJson(any(), any())).thenThrow(new ResourceAccessException(
                "POST https://api.telegram.org/botfake-stage3-token/getUpdates text=/secret-command"));
        var client = new TelegramApiClient(http, properties());

        Throwable failure = catchThrowable(() -> client.getUpdates(null, Duration.ZERO, 50));

        assertThat(failure).isInstanceOf(TelegramTransportException.class);
        assertThat(failure.getMessage()).doesNotContain("fake-stage3-token", "/secret-command", "https://");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void sendsMessagesAndAnswersCallbacksThroughTypedOperations() throws Exception {
        ExternalHttpClient http = org.mockito.Mockito.mock(ExternalHttpClient.class);
        when(http.postJson(any(), any())).thenReturn(json.readTree("{\"ok\":true,\"result\":{}}"));
        var client = new TelegramApiClient(http, properties());

        client.sendMessage("-100555", "safe", java.util.List.of());
        client.answerCallbackQuery("cb-1", "Saved");

        verify(http).postJson(contains("/sendMessage"), any());
        verify(http).postJson(contains("/answerCallbackQuery"), any());
    }

    @Test
    void enabledConfigurationFailsClosedWithoutExplicitAuthorization() {
        Throwable failure = catchThrowable(() -> new JobPilotProperties.Telegram(
                "fake-token", "-100555", "JobPilotBot", true, "", "777", Duration.ofSeconds(25),
                Duration.ofSeconds(2), 50, 3, true));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledConfigurationNormalizesAndValidatesBotUsername() {
        var telegram = new JobPilotProperties.Telegram(
                "fake-token", "-100555", "@JobPilotBot", true, "-100555", "777",
                Duration.ofSeconds(25), Duration.ofSeconds(2), 50, 3, true);
        assertThat(telegram.botUsername()).isEqualTo("JobPilotBot");

        Throwable missing = catchThrowable(() -> new JobPilotProperties.Telegram(
                "fake-token", "-100555", "", true, "-100555", "777",
                Duration.ofSeconds(25), Duration.ofSeconds(2), 50, 3, true));
        Throwable invalid = catchThrowable(() -> new JobPilotProperties.Telegram(
                "fake-token", "-100555", "not a telegram bot", true, "-100555", "777",
                Duration.ofSeconds(25), Duration.ofSeconds(2), 50, 3, true));
        assertThat(missing).isInstanceOf(IllegalArgumentException.class);
        assertThat(invalid).isInstanceOf(IllegalArgumentException.class);
    }

    private JobPilotProperties properties() {
        return TestProperties.create(new JobPilotProperties.Telegram(
                "fake-stage3-token", "-100555", "JobPilotBot", true, "-100555", "777",
                Duration.ofSeconds(25), Duration.ofSeconds(2), 50, 3, true));
    }
}
