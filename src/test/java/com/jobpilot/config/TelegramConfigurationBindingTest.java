package com.jobpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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

    @Test
    void documentedEnvironmentVariablesBindThroughTheRealApplicationYaml() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "telegram-test-systemEnvironment", TELEGRAM_ENVIRONMENT));
        SpringApplication application = new SpringApplication(TelegramPropertiesConfiguration.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.setBannerMode(Banner.Mode.OFF);

        try (ConfigurableApplicationContext context = application.run()) {
            JobPilotProperties.Telegram settings = context
                    .getBean(JobPilotProperties.class).telegram();
            assertThat(settings.enabled()).isTrue();
            assertThat(settings.commandsEnabled()).isTrue();
            assertThat(settings.pollingEnabled()).isTrue();
            assertThat(settings.discardPendingOnFirstStart()).isFalse();
            assertThat(settings.pollDelay()).isEqualTo(Duration.ofSeconds(2));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JobPilotProperties.class)
    static class TelegramPropertiesConfiguration {
    }
}
