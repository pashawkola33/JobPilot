package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.config.JobPilotProperties.Telegram;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelegramReviewConfigurationTest {
    private static final String TOKEN = "1234567890:obviously-fake-bot-token-value";

    private static Telegram review(boolean enabled, List<String> chatIds) {
        return new Telegram(enabled ? TOKEN : "", enabled, chatIds, true, true, 5, 500);
    }

    @Test
    void defaultsToDisabledWithNoTokenAndNoChats() {
        Telegram settings = new Telegram("", "");

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.botToken()).isEmpty();
        assertThat(settings.allowedChatIds()).isEmpty();
        assertThat(settings.pollingEnabled()).isFalse();
        assertThat(settings.channelConfigured()).isFalse();
    }

    @Test
    void defaultsEnableBothNotificationSurfacesWithBoundedSizes() {
        Telegram settings = new Telegram("", "");

        assertThat(settings.matchNotificationsEnabled()).isTrue();
        assertThat(settings.reviewDigestEnabled()).isTrue();
        assertThat(settings.maxJobsPerMessage()).isEqualTo(5);
        assertThat(settings.maxNoteLength()).isEqualTo(500);
        assertThat(settings.pollTimeout().toSeconds()).isEqualTo(25);
    }

    @Test
    void rejectsEnabledBotWithoutToken() {
        assertThatThrownBy(() -> new Telegram("", true, List.of("777"), true, true, 5, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bot token");
    }

    @Test
    void rejectsEnabledBotWithoutChatIds() {
        assertThatThrownBy(() -> review(true, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed numeric chat id");
    }

    @Test
    void rejectsMalformedChatIds() {
        for (String malformed : List.of("abc", "12a", "0", "1.5", "--7", "99999999999999999999")) {
            assertThatThrownBy(() -> review(true, List.of(malformed)))
                    .as(malformed)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("numeric Telegram chat identifiers");
        }
    }

    @Test
    void rejectsDuplicateChatIds() {
        assertThatThrownBy(() -> review(true, List.of("777", " 777 ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicates");
    }

    @Test
    void stripsWhitespaceAndDropsEmptyEntries() {
        Telegram settings = review(true, List.of("  777  ", "", "   ", "-100555"));

        assertThat(settings.allowedChatIds()).containsExactly("777", "-100555");
        assertThat(settings.allowsChat(777L)).isTrue();
        assertThat(settings.allowsChat(-100555L)).isTrue();
        assertThat(settings.allowsChat(778L)).isFalse();
    }

    @Test
    void rejectsOutOfRangeReviewBounds() {
        assertThatThrownBy(() -> new Telegram(TOKEN, true, List.of("777"), true, true, 0, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Telegram(TOKEN, true, List.of("777"), true, true, 11, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Telegram(TOKEN, true, List.of("777"), true, true, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Telegram(TOKEN, true, List.of("777"), true, true, 5, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsReviewBoundsAtTheirLimits() {
        assertThat(new Telegram(TOKEN, true, List.of("777"), true, true, 1, 1).maxNoteLength())
                .isEqualTo(1);
        assertThat(new Telegram(TOKEN, true, List.of("777"), false, false, 10, 1000)
                .maxJobsPerMessage()).isEqualTo(10);
    }

    @Test
    void neverExposesTheBotTokenInToString() {
        String rendered = review(true, List.of("777")).toString();

        assertThat(rendered).doesNotContain(TOKEN).doesNotContain("obviously-fake");
        assertThat(rendered).contains("<redacted>").contains("1 configured");
        assertThat(rendered).doesNotContain("777");
    }

    @Test
    void reportsEmptyRatherThanRedactedWhenNoTokenIsConfigured() {
        assertThat(new Telegram("", "").toString()).contains("<empty>");
    }

    @Test
    void enablingTheReviewBotAloneTurnsPollingOn() {
        assertThat(review(true, List.of("777")).pollingEnabled()).isTrue();
        assertThat(review(false, List.of()).pollingEnabled()).isFalse();
    }
}
