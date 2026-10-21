package com.jobpilot.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.event.ContextRefreshedEvent;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RecoverySchemaCompatibilityIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach void supportedSchema() {
        var flyway = Flyway.configure().dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .cleanDisabled(false).target("20").load();
        flyway.clean();
        flyway.migrate();
    }

    @Test void supportedV20StartsInRecoveryAndNormalMode() {
        for (String profile : new String[]{"recovery", "normal"}) {
            RecoveryConfigurationTest.recovery(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                    .withPropertyValues("spring.profiles.active=" + profile)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"recovery", "normal"})
    void committedUnknownV21CannotServeOrRefreshInEitherMode(String profile) throws Exception {
        execute("insert into flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
                + "values(21,'21','unknown future schema','SQL','V21__unknown.sql',123,'synthetic',0,true)");
        refusesStartup(profile);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "update flyway_schema_history set checksum=123 where version='1'",
            "update flyway_schema_history set success=false where version='20'",
            "delete from flyway_schema_history where version='16'",
            "delete from flyway_schema_history where version='20'"})
    void incompatibleHistoryCannotStartRecovery(String corruption) throws Exception {
        execute(corruption);
        refusesStartup("recovery");
    }

    @Test void recoveryCannotSuppressMissingMigrationValidation() throws Exception {
        execute("insert into flyway_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) "
                + "values(21,'21','unknown future schema','SQL','V21__unknown.sql',123,'synthetic',0,true)");
        RecoveryConfigurationTest.recovery(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .withPropertyValues("spring.flyway.ignore-migration-patterns=*:future")
                .run(context -> assertThat(context).hasFailed());
    }

    private void refusesStartup(String profile) {
        var refreshed = new AtomicBoolean();
        RecoveryConfigurationTest.recovery(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .withPropertyValues("spring.profiles.active=" + profile)
                .withInitializer(context -> context.addApplicationListener(event -> {
                    if (event instanceof ContextRefreshedEvent) refreshed.set(true);
                })).run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(refreshed).isFalse();
                });
    }

    private void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
