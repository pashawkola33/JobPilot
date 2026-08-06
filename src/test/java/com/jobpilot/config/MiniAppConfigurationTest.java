package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jobpilot.config.JobPilotProperties.MiniApp;
import com.jobpilot.support.TestProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MiniAppConfigurationTest {

    @Test
    void defaultsToDisabledWithNobodyAllowed() {
        MiniApp settings = MiniApp.disabled();

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.allowedUserIds()).isEmpty();
        assertThat(settings.allows(1L)).isFalse();
    }

    @Test
    void treatsAnAbsentSectionAsDisabled() {
        assertThat(TestProperties.create().miniApp().enabled()).isFalse();
    }

    @Test
    void refusesToEnableWithoutAnAllowList() {
        // The failure mode this exists to prevent: an empty list meaning "everyone".
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MiniApp(true, List.of(), Duration.ofHours(1)))
                .withMessageContaining("at least one allowed numeric Telegram user id");
    }

    @Test
    void refusesToEnableWithoutABotToken() {
        assertThatIllegalArgumentException().isThrownBy(() -> TestProperties.create(
                        new JobPilotProperties.Telegram("", ""),
                        new MiniApp(true, List.of("4242"), Duration.ofHours(1))))
                .withMessageContaining("bot-token");
    }

    @Test
    void enablesWithABotTokenAndAnExplicitUser() {
        assertThatCode(() -> TestProperties.create(
                new JobPilotProperties.Telegram("123456:token", ""),
                new MiniApp(true, List.of("4242"), Duration.ofHours(1)))).doesNotThrowAnyException();
    }

    @Test
    void allowsOnlyTheConfiguredUsers() {
        MiniApp settings = new MiniApp(true, List.of("4242", "77"), Duration.ofHours(1));

        assertThat(settings.allows(4242L)).isTrue();
        assertThat(settings.allows(77L)).isTrue();
        assertThat(settings.allows(4243L)).isFalse();
        assertThat(settings.allows(-4242L)).isFalse();
    }

    @Test
    void ignoresBlankEntriesButRejectsUnusableOnes() {
        assertThat(new MiniApp(false, Arrays.asList("4242", "", "  ", null), Duration.ofHours(1))
                .allowedUserIds()).containsExactly("4242");

        for (String invalid : List.of("0", "-1", "abc", "42.0", "4242 ,77")) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new MiniApp(false, List.of(invalid), Duration.ofHours(1)));
        }
    }

    @Test
    void rejectsDuplicateUserIds() {
        assertThatIllegalArgumentException().isThrownBy(
                        () -> new MiniApp(false, List.of("4242", "4242"), Duration.ofHours(1)))
                .withMessageContaining("duplicates");
    }

    @Test
    void keepsTheAuthenticationWindowInsideItsSafeBounds() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofHours(25))) {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> new MiniApp(false, List.of(), invalid));
        }
        assertThat(new MiniApp(false, List.of(), null).maxAuthAge())
                .isEqualTo(MiniApp.DEFAULT_MAX_AUTH_AGE);
    }

    @Test
    void keepsUserIdsOutOfItsOwnToString() {
        String text = new MiniApp(true, List.of("4242"), Duration.ofHours(1)).toString();

        assertThat(text).doesNotContain("4242").contains("1 configured");
    }
}
