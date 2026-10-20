package com.jobpilot.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.config.MaintenanceProperties;
import com.jobpilot.identity.IdentityConfigurationGuard;
import com.jobpilot.identity.IdentityProperties;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class RecoveryConfigurationTest {
    static ApplicationContextRunner recovery(String url, String username, String password) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, FlywayAutoConfiguration.class))
                .withUserConfiguration(ConfigurationUnderTest.class)
                .withPropertyValues("spring.profiles.active=recovery", "spring.datasource.url=" + url,
                        "spring.datasource.username=" + username, "spring.datasource.password=" + password,
                        "jobpilot.identity.legacy-owner-telegram-user-id=4242",
                        "jobpilot.mini-app.allowed-user-ids=4242", "jobpilot.telegram.bot-token=synthetic-recovery-token");
    }

    private ApplicationContextRunner recovery() {
        return recovery("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Test void profilePreservesOwnerAccessAndClosesEveryBackgroundSurface() {
        recovery().withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("synthetic-env", Map.of(
                        "IDENTITY_REGISTRATION_ENABLED", "true", "JOBPILOT_SCHEDULING_ENABLED", "true",
                        "TELEGRAM_BOT_ENABLED", "true", "TELEGRAM_CHANNEL_ID", "4242",
                        "TELEGRAM_COMMANDS_ENABLED", "true", "MAINTENANCE_ENABLED", "true"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var identity = context.getBean(IdentityProperties.class);
                    var properties = context.getBean(JobPilotProperties.class);
                    assertThat(identity.enabled()).isTrue();
                    assertThat(identity.admits(4242)).isTrue();
                    assertThat(identity.registrationEnabled()).isFalse();
                    assertThat(properties.miniApp().enabled()).isTrue();
                    assertThat(properties.miniApp().allows(4242)).isTrue();
                    assertThat(properties.telegram().pollingEnabled()).isFalse();
                    assertThat(properties.telegram().channelConfigured()).isFalse();
                    assertThat(properties.telegram().matchNotificationsEnabled()).isFalse();
                    assertThat(properties.telegram().reviewDigestEnabled()).isFalse();
                    assertThat(context.getBean(MaintenanceProperties.class).enabled()).isFalse();
                    assertThat(context.getEnvironment().getProperty("jobpilot.score-rescore-preview.enabled", Boolean.class)).isFalse();
                    assertThat(context.getEnvironment().getProperty("jobpilot.scheduled-tasks-enabled", Boolean.class)).isFalse();
                });
    }

    @Test void normalModeCannotBypassSchemaValidationByDisablingFlyway() {
        recovery().withPropertyValues("spring.profiles.active=normal", "spring.flyway.enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jobpilot.identity.registration-enabled=true", "jobpilot.identity.enabled=false",
            "jobpilot.mini-app.enabled=false", "jobpilot.scheduled-tasks-enabled=true",
            "jobpilot.telegram.enabled=true", "jobpilot.telegram.commands-enabled=true",
            "jobpilot.telegram.channel-id=4242", "jobpilot.telegram.match-notifications-enabled=true",
            "jobpilot.telegram.review-digest-enabled=true", "jobpilot.maintenance.enabled=true",
            "spring.flyway.enabled=false", "spring.flyway.target=17", "spring.flyway.validate-on-migrate=false",
            "spring.flyway.ignore-migration-patterns=*:future",
            "jobpilot.score-rescore-command.mode=APPLY", "jobpilot.source-log-cleanup.mode=APPLY",
            "jobpilot.score-rescore-preview.enabled=true", "jobpilot.personalized-matching.enabled=true" })
    void rejectsUnsafeOverrideBeforeContextRefresh(String override) {
        var refreshed = new AtomicBoolean();
        recovery().withPropertyValues("jobpilot.telegram.allowed-chat-ids=4242",
                "jobpilot.telegram.bot-username=recoverybot", "jobpilot.telegram.allowed-chat-id=4242",
                "jobpilot.telegram.allowed-user-id=4242", override)
                .withInitializer(context -> context.addApplicationListener(event -> {
                    if (event instanceof ContextRefreshedEvent) refreshed.set(true);
                })).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(refreshed).isFalse();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"jobpilot.identity.legacy-owner-telegram-user-id=",
            "jobpilot.mini-app.allowed-user-ids=9002"})
    void stillRequiresExplicitOwnerAndMatchingMiniAppAllowlist(String override) {
        recovery().withPropertyValues(override).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOBPILOT_IDENTITY_REGISTRATION_ENABLED", "JOBPILOT_SCORE_RESCORE_PREVIEW_ENABLED"})
    void relaxedEnvironmentOverrideCannotStartUnsafeRecovery(String variable) {
        var refreshed = new AtomicBoolean();
        recovery().withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("synthetic-override", Map.of(
                        variable, "true"))))
                .withInitializer(context -> context.addApplicationListener(event -> {
                    if (event instanceof ContextRefreshedEvent) refreshed.set(true);
                })).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(refreshed).isFalse();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({JobPilotProperties.class, IdentityProperties.class,
            MaintenanceProperties.class, CandidateProfileProperties.class})
    @Import(IdentityConfigurationGuard.class)
    @ComponentScan(basePackages = "com.jobpilot.config", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*(RecoveryConfigurationGuard|SchemaCompatibilityGuard)"))
    static class ConfigurationUnderTest { }
}
