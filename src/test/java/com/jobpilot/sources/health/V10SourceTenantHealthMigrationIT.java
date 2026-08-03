package com.jobpilot.sources.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V10 on real PostgreSQL, starting from the V9 schema with legacy rows already present.
 * H2's PostgreSQL mode does not guarantee identical DDL, CHECK, or UUID semantics.
 */
@Testcontainers(disabledWithoutDocker = true)
class V10SourceTenantHealthMigrationIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void appliesAfterV9AndKeepsLegacyAggregateRowsValid() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).target("9").load().migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO source_fetch_logs(source_name, started_at, finished_at, status,
                        fetched_count, saved_count)
                    VALUES ('greenhouse', now(), now(), 'SUCCESS', 12, 3)
                    """);
        }

        var result = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).target("10").load().migrate();
        assertThat(result.targetSchemaVersion).isEqualTo("10");

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // The legacy aggregate row survives and its new correlation column is null.
            try (var rows = statement.executeQuery(
                    "SELECT ingestion_run_id, fetched_count FROM source_fetch_logs "
                            + "WHERE source_name = 'greenhouse'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("ingestion_run_id")).isNull();
                assertThat(rows.getInt("fetched_count")).isEqualTo(12);
            }

            assertThat(tables(statement))
                    .contains("source_tenant_fetch_logs", "source_tenant_health");
            assertThat(indexes(statement, "source_tenant_health")).contains(
                    "source_tenant_health_uk",
                    "source_tenant_health_provider_tenant_idx",
                    "source_tenant_health_last_attempt_idx",
                    "source_tenant_health_last_success_idx",
                    "source_tenant_health_consecutive_failures_idx",
                    "source_tenant_health_category_idx");
            assertThat(indexes(statement, "source_tenant_fetch_logs")).contains(
                    "source_tenant_fetch_logs_provider_tenant_idx",
                    "source_tenant_fetch_logs_run_idx",
                    "source_tenant_fetch_logs_started_idx",
                    "source_tenant_fetch_logs_category_idx");
            assertThat(checkConstraints(statement, "source_tenant_health")).contains(
                    "source_tenant_health_status_ck", "source_tenant_health_category_ck",
                    "source_tenant_health_counts_ck", "source_tenant_health_http_status_ck");
            assertThat(checkConstraints(statement, "source_tenant_fetch_logs")).contains(
                    "source_tenant_fetch_logs_status_ck", "source_tenant_fetch_logs_category_ck",
                    "source_tenant_fetch_logs_counts_ck",
                    "source_tenant_fetch_logs_http_status_ck");
        }
    }

    @Test
    void enforcesEnumCheckConstraintsAndTenantUniquenessOnPostgres() throws Exception {
        // Pinned to V10: this test owns V10's constraints; V11 widens them separately.
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).target("10").load().migrate();

        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            // A native UUID round-trips, proving the column type matches the entity mapping.
            UUID run = UUID.randomUUID();
            statement.executeUpdate(attempt(run, "ashby", "cohere", "FAILURE", "INVALID_TENANT", 404));
            try (var rows = statement.executeQuery(
                    "SELECT ingestion_run_id FROM source_tenant_fetch_logs WHERE tenant = 'cohere'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("ingestion_run_id", UUID.class)).isEqualTo(run);
            }

            assertThatThrownBy(() -> statement.executeUpdate(
                    attempt(UUID.randomUUID(), "ashby", "x", "NOT_A_STATUS", "NONE", null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    attempt(UUID.randomUUID(), "ashby", "x", "FAILURE", "NOT_A_CATEGORY", null)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    attempt(UUID.randomUUID(), "ashby", "x", "FAILURE", "SERVER_ERROR", 42)))
                    .isInstanceOf(SQLException.class);

            statement.executeUpdate(healthRow("lever", "veeva", 2, 5, 3, 2));
            assertThatThrownBy(() -> statement.executeUpdate(healthRow("lever", "veeva", 0, 1, 1, 0)))
                    .isInstanceOf(SQLException.class);
            // total_attempts must equal successes + failures.
            assertThatThrownBy(() -> statement.executeUpdate(healthRow("lever", "other", 0, 9, 1, 1)))
                    .isInstanceOf(SQLException.class);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private String attempt(UUID run, String provider, String tenant, String status,
                           String category, Integer httpStatus) {
        return """
                INSERT INTO source_tenant_fetch_logs(ingestion_run_id, provider, tenant,
                    attempt_status, failure_category, http_status, fetched_count, duration_ms,
                    started_at, finished_at)
                VALUES ('%s', '%s', '%s', '%s', '%s', %s, 0, 10, now(), now())
                """.formatted(run, provider, tenant, status, category,
                httpStatus == null ? "NULL" : httpStatus.toString());
    }

    private String healthRow(String provider, String tenant, int consecutiveFailures,
                             long attempts, long successes, long failures) {
        return """
                INSERT INTO source_tenant_health(provider, tenant, last_attempt_status,
                    last_failure_category, last_fetched_count, last_duration_ms,
                    consecutive_failures, total_attempts, total_successes, total_failures,
                    created_at, updated_at)
                VALUES ('%s', '%s', 'FAILURE', 'SERVER_ERROR', 0, 5, %d, %d, %d, %d, now(), now())
                """.formatted(provider, tenant, consecutiveFailures, attempts, successes, failures);
    }

    private List<String> tables(Statement statement) throws SQLException {
        List<String> names = new java.util.ArrayList<>();
        try (var rows = statement.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private List<String> indexes(Statement statement, String table) throws SQLException {
        List<String> names = new java.util.ArrayList<>();
        try (var rows = statement.executeQuery(
                "SELECT indexname FROM pg_indexes WHERE tablename = '" + table + "'")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    private List<String> checkConstraints(Statement statement, String table) throws SQLException {
        List<String> names = new java.util.ArrayList<>();
        try (var rows = statement.executeQuery(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' "
                        + "AND conrelid = '" + table + "'::regclass")) {
            while (rows.next()) names.add(rows.getString(1));
        }
        return names;
    }
}
