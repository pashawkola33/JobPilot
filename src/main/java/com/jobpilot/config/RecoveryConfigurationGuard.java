package com.jobpilot.config;

import com.jobpilot.identity.IdentityProperties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails before ContextRefreshedEvent can start scheduled tasks or accept traffic. */
@Component
@Profile("recovery")
public class RecoveryConfigurationGuard implements SmartInitializingSingleton {
    private final IdentityProperties identity;
    private final JobPilotProperties properties;
    private final MaintenanceProperties maintenance;
    private final Environment environment;
    private final ObjectProvider<Flyway> flyway;

    public RecoveryConfigurationGuard(IdentityProperties identity, JobPilotProperties properties,
            MaintenanceProperties maintenance, Environment environment, ObjectProvider<Flyway> flyway) {
        this.identity = identity;
        this.properties = properties;
        this.maintenance = maintenance;
        this.environment = environment;
        this.flyway = flyway;
    }

    @Override public void afterSingletonsInstantiated() {
        var telegram = properties.telegram();
        if (!identity.enabled() || identity.registrationEnabled() || !properties.miniApp().enabled()
                || environment.getProperty("jobpilot.scheduled-tasks-enabled", Boolean.class, true)
                || telegram.pollingEnabled() || telegram.channelConfigured()
                || telegram.matchNotificationsEnabled() || telegram.reviewDigestEnabled()
                || maintenance.enabled()
                || environment.getProperty("jobpilot.personalized-matching.enabled", Boolean.class, false)
                || environment.getProperty("jobpilot.score-rescore-preview.enabled", Boolean.class, false)
                || !"OFF".equalsIgnoreCase(environment.getProperty("jobpilot.score-rescore-command.mode", "OFF"))
                || !"OFF".equalsIgnoreCase(environment.getProperty("jobpilot.source-log-cleanup.mode", "OFF"))) {
            throw new IllegalArgumentException("Recovery requires owner access, closed beta admission, and disabled scheduling, Telegram, maintenance and one-shot commands");
        }
        Flyway migrations = flyway.getIfAvailable();
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, true) || migrations == null
                || !MigrationVersion.fromVersion("19").equals(migrations.getConfiguration().getTarget())
                || !migrations.getConfiguration().isValidateOnMigrate()
                || migrations.getConfiguration().getIgnoreMigrationPatterns().length != 0) {
            throw new IllegalArgumentException("Recovery requires enabled Flyway, target V19 and strict migration validation");
        }
    }
}
