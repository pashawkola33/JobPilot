package com.jobpilot.sources.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V11 on real PostgreSQL, starting from V10 with history already recorded. H2's
 * PostgreSQL mode does not guarantee identical CHECK-constraint semantics.
 */
@Testcontainers(disabledWithoutDocker = true)
class V11ResponseTooLargeCategoryMigrationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID LEGACY_RUN = UUID.randomUUID();

    @Test
    void appliesAfterV10PreservingHistoryAndAdmittingTheNewCategory() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).target("10").load().migrate();

        // A row recorded under the old 2 MiB limit, before RESPONSE_TOO_LARGE existed.
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO source_tenant_fetch_logs(ingestion_run_id, provider, tenant,
                        attempt_status, failure_category, http_status, fetched_count, duration_ms,
                        error_type, error_message, started_at, finished_at)
                    VALUES ('%s', 'greenhouse', 'gitlab', 'FAILURE', 'RESPONSE_PARSE_ERROR', NULL,
                        0, 2073, 'com.jobpilot.common.ExternalHttpException',
                        'Response exceeded the configured size limit for greenhouse tenant gitlab',
                        now(), now())
                    """.formatted(LEGACY_RUN));
            statement.executeUpdate("""
                    INSERT INTO source_tenant_health(provider, tenant, last_attempt_status,
                        last_failure_category, last_fetched_count, last_duration_ms,
                        consecutive_failures, total_attempts, total_successes, total_failures,
                        created_at, updated_at)
                    VALUES ('greenhouse', 'gitlab', 'FAILURE', 'RESPONSE_PARSE_ERROR', 0, 2073,
                        2, 2, 0, 2, now(), now())
                    """);
        }

        var result = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("11");

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            assertThat(successfulMigrations(statement)).isEqualTo(11);
            assertThat(appliedCount(statement, "11")).isEqualTo(1);

            // Historical rows are preserved byte-for-byte, not rewritten to the new value.
            try (var rows = statement.executeQuery("""
                    SELECT ingestion_run_id, failure_category, duration_ms, error_message
                    FROM source_tenant_fetch_logs WHERE tenant = 'gitlab'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("ingestion_run_id", UUID.class)).isEqualTo(LEGACY_RUN);
                assertThat(rows.getString("failure_category")).isEqualTo("RESPONSE_PARSE_ERROR");
                assertThat(rows.getLong("duration_ms")).isEqualTo(2073L);
                assertThat(rows.getString("error_message")).startsWith("Response exceeded");
            }
            try (var rows = statement.executeQuery("""
                    SELECT last_failure_category, consecutive_failures, total_attempts,
                           total_successes, total_failures
                    FROM source_tenant_health WHERE tenant = 'gitlab'
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("last_failure_category")).isEqualTo("RESPONSE_PARSE_ERROR");
                assertThat(rows.getInt("consecutive_failures")).isEqualTo(2);
                assertThat(rows.getLong("total_attempts")).isEqualTo(2L);
                assertThat(rows.getLong("total_failures")).isEqualTo(2L);
            }

            // Both widened constraints now admit the new category.
            statement.executeUpdate(attempt("ashby", "cohere", "RESPONSE_TOO_LARGE"));
            statement.executeUpdate(health("ashby", "cohere", "RESPONSE_TOO_LARGE"));

            // ...and still reject anything outside the closed set.
            assertThatThrownBy(() -> statement.executeUpdate(
                    attempt("ashby", "bogus", "NOT_A_CATEGORY"))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    health("ashby", "bogus", "NOT_A_CATEGORY"))).isInstanceOf(SQLException.class);

            // Uniqueness, counter invariants, and indexes survive the constraint swap.
            assertThatThrownBy(() -> statement.executeUpdate(
                    health("ashby", "cohere", "RESPONSE_TOO_LARGE"))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("""
                    INSERT INTO source_tenant_health(provider, tenant, last_attempt_status,
                        last_failure_category, last_fetched_count, last_duration_ms,
                        consecutive_failures, total_attempts, total_successes, total_failures,
                        created_at, updated_at)
                    VALUES ('ashby', 'skewed', 'FAILURE', 'RESPONSE_TOO_LARGE', 0, 1, 1, 9, 1, 1,
                        now(), now())
                    """)).isInstanceOf(SQLException.class);
            assertThat(indexes(statement)).contains(
                    "source_tenant_health_uk",
                    "source_tenant_health_provider_tenant_idx",
                    "source_tenant_health_category_idx",
                    "source_tenant_fetch_logs_provider_tenant_idx",
                    "source_tenant_fetch_logs_category_idx");
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private String attempt(String provider, String tenant, String category) {
        return """
                INSERT INTO source_tenant_fetch_logs(ingestion_run_id, provider, tenant,
                    attempt_status, failure_category, fetched_count, duration_ms,
                    started_at, finished_at)
                VALUES ('%s', '%s', '%s', 'FAILURE', '%s', 0, 10, now(), now())
                """.formatted(UUID.randomUUID(), provider, tenant, category);
    }

    private String health(String provider, String tenant, String category) {
        return """
                INSERT INTO source_tenant_health(provider, tenant, last_attempt_status,
                    last_failure_category, last_fetched_count, last_duration_ms,
                    consecutive_failures, total_attempts, total_successes, total_failures,
                    created_at, updated_at)
                VALUES ('%s', '%s', 'FAILURE', '%s', 0, 10, 1, 1, 0, 1, now(), now())
                """.formatted(provider, tenant, category);
    }

    private int successfulMigrations(Statement statement) throws SQLException {
        try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private int appliedCount(Statement statement, String version) throws SQLException {
        try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '" + version + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private java.util.List<String> indexes(Statement statement) throws SQLException {
        java.util.List<String> names = new java.util.ArrayList<>();
        try (var rows = statement.executeQuery(
                "SELECT indexname FROM pg_indexes WHERE tablename IN "
                        + "('source_tenant_health', 'source_tenant_fetch_logs')")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }
}
