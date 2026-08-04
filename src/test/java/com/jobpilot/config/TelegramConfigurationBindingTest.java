package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class TelegramConfigurationBindingTest {
    private static final String DUMMY_TOKEN = "123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi";
    private static final Map<String, Object> TELEGRAM_ENVIRONMENT = Map.of(
            "TELEGRAM_BOT_ENABLED", "true",
            "TELEGRAM_COMMANDS_ENABLED", "true",
            "TELEGRAM_BOT_TOKEN", DUMMY_TOKEN,
            "TELEGRAM_BOT_USERNAME", "jobpilotsearchbot",
            "TELEGRAM_ALLOWED_CHAT_ID", "123456789",
            "TELEGRAM_ALLOWED_USER_ID", "123456789",
            "TELEGRAM_ALLOWED_CHAT_IDS", "123456789",
            "TELEGRAM_DISCARD_PENDING_ON_FIRST_START", "false");

    /** Binds the given environment through the real application.yaml and returns the settings. */
    private JobPilotProperties.Telegram bind(Map<String, Object> environmentVariables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "telegram-test-systemEnvironment", environmentVariables));
        SpringApplication application = new SpringApplication(TelegramPropertiesConfiguration.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setBannerMode(Banner.Mode.OFF);
        try (ConfigurableApplicationContext context = application.run()) {
            return context.getBean(JobPilotProperties.class).telegram();
        }
    }

    private Map<String, Object> productionLike(Map<String, String> overrides) {
        Map<String, Object> environment = new HashMap<>(TELEGRAM_ENVIRONMENT);
        environment.putAll(overrides);
        return environment;
    }

    @Test
    void documentedEnvironmentVariablesBindThroughTheRealApplicationYaml() {
        JobPilotProperties.Telegram settings = bind(TELEGRAM_ENVIRONMENT);

        assertThat(settings.enabled()).isTrue();
        assertThat(settings.commandsEnabled()).isTrue();
        assertThat(settings.pollingEnabled()).isTrue();
        assertThat(settings.discardPendingOnFirstStart()).isFalse();
        assertThat(settings.pollDelay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void defaultPollTimeoutStaysBelowTheHttpResponseTimeout() {
        JobPilotProperties.Telegram settings = bind(TELEGRAM_ENVIRONMENT);

        assertThat(settings.pollTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(JobPilotProperties.Telegram.DEFAULT_POLL_TIMEOUT)
                .isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void durationFormVariableBindsToFifteenSeconds() {
        JobPilotProperties.Telegram settings =
                bind(productionLike(Map.of("TELEGRAM_POLL_TIMEOUT", "15s")));

        assertThat(settings.pollTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void wholeSecondVariableBindsToFifteenSecondsWhenTheDurationFormIsAbsent() {
        JobPilotProperties.Telegram settings =
                bind(productionLike(Map.of("TELEGRAM_POLLING_TIMEOUT_SECONDS", "15")));

        assertThat(settings.pollTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void productionStyleConfigurationKeepsPollingEnabled() {
        JobPilotProperties.Telegram settings = bind(productionLike(Map.of(
                "TELEGRAM_POLL_TIMEOUT", "15s",
                "TELEGRAM_POLLING_TIMEOUT_SECONDS", "15")));

        assertThat(settings.pollingEnabled()).isTrue();
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.commandsEnabled()).isTrue();
        assertThat(settings.pollTimeout()).isEqualTo(Duration.ofSeconds(15));
    }

    /** The whole-second form must never win over an explicit Duration-form override. */
    @Test
    void durationFormTakesPrecedenceOverTheWholeSecondForm() {
        JobPilotProperties.Telegram settings = bind(productionLike(Map.of(
                "TELEGRAM_POLL_TIMEOUT", "12s",
                "TELEGRAM_POLLING_TIMEOUT_SECONDS", "18")));

        assertThat(settings.pollTimeout()).isEqualTo(Duration.ofSeconds(12));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobPilotProperties.class)
    static class TelegramPropertiesConfiguration {
    }
}
