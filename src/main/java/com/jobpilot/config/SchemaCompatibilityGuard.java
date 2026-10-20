package com.jobpilot.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/** This candidate-aware artifact supports only its complete, validated V19 migration history. */
@Component
public class SchemaCompatibilityGuard implements SmartInitializingSingleton {
    private static final MigrationVersion SUPPORTED = MigrationVersion.fromVersion("19");
    private final ObjectProvider<Flyway> flyway;

    public SchemaCompatibilityGuard(ObjectProvider<Flyway> flyway) {
        this.flyway = flyway;
    }

    @Override public void afterSingletonsInstantiated() {
        Flyway migrations = flyway.getIfAvailable();
        if (migrations == null) throw new IllegalStateException("Candidate-aware runtime requires enabled Flyway and validated schema V19");
        migrations.validate();
        var info = migrations.info();
        for (var migration : info.all()) {
            if (migration.getState() != MigrationState.SUCCESS || migration.getVersion() == null
                    || migration.getVersion().compareTo(SUPPORTED) > 0) {
                throw new IllegalStateException("Candidate-aware runtime requires validated schema V19; incompatible migration V"
                        + migration.getVersion() + " state=" + migration.getState().getDisplayName());
            }
        }
        if (info.current() == null || !SUPPORTED.equals(info.current().getVersion())) {
            throw new IllegalStateException("Candidate-aware runtime requires validated schema V19");
        }
    }
}
