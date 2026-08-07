package com.jobpilot.jobreview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V12 is verified on H2 in PostgreSQL mode, exactly like the V8 and V9 migration tests.
 * No container, no network, no live database.
 */
class V12JobReviewWorkflowMigrationTest {
    private static final String INSERT_JOB = """
            INSERT INTO jobs (source, provider_tenant, external_id, canonical_url, title, company,
                              location, description, status, fetched_at, first_seen_at,
                              last_seen_at, raw_payload_hash, description_hash,
                              normalized_fingerprint)
            VALUES ('greenhouse', 'acme', 'ext-1', 'https://example.test/jobs/1', 'Java Intern',
                    'Acme', 'Bucharest', 'Description', 'NEW', CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'h1', 'h2', 'h3')
            """;

    private String migrate(String name) throws Exception {
        String url = "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        return url;
    }

    private long seedJob(Statement statement) throws Exception {
        statement.executeUpdate(INSERT_JOB);
        try (var rows = statement.executeQuery("SELECT id FROM jobs WHERE external_id = 'ext-1'")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong("id");
        }
    }

    @Test
    void appliesVersionTwelveAndItsTables() throws Exception {
        String url = migrate("v12apply");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            // V12 specifically, not "the newest migration in the repository". Asserting the max
            // version made every later migration fail a test about V12 — which says nothing
            // about V12, and only invites bumping the number until it stops meaning anything.
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) c FROM flyway_schema_history "
                            + "WHERE version = '12' AND success = TRUE")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isEqualTo(1);
            }
            for (String table : new String[] {"job_workflow_state", "telegram_job_delivery"}) {
                try (var rows = statement.executeQuery(
                        "SELECT COUNT(*) c FROM information_schema.tables WHERE table_name = '"
                                + table + "'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("c")).as(table).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void enforcesWorkflowStatusAndNoteAndAppliedAtConstraints() throws Exception {
        String url = migrate("v12constraints");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long jobId = seedJob(statement);

            assertThatThrownBy(() -> statement.executeUpdate(workflow(jobId, "MAYBE", "NULL", "NULL")))
                    .as("invalid status").isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    workflow(jobId, "APPLIED", "NULL", "NULL")))
                    .as("APPLIED without applied_at").isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    workflow(jobId, "SAVED", "NULL", "CURRENT_TIMESTAMP")))
                    .as("non-APPLIED with applied_at").isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    workflow(jobId, "SAVED", "'" + "n".repeat(1001) + "'", "NULL")))
                    .as("note over 1000").isInstanceOf(java.sql.SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(workflow(jobId, "SAVED", "'   '", "NULL")))
                    .as("blank note").isInstanceOf(java.sql.SQLException.class);

            statement.executeUpdate(workflow(jobId, "SAVED", "'keep in mind'", "NULL"));
            assertThatThrownBy(() -> statement.executeUpdate(workflow(jobId, "SAVED", "NULL", "NULL")))
                    .as("one row per job").isInstanceOf(java.sql.SQLException.class);
        }
    }

    @Test
    void enforcesDeliveryUniquenessAndTypeConstraint() throws Exception {
        String url = migrate("v12delivery");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long jobId = seedJob(statement);

            statement.executeUpdate(delivery(777, jobId, "MATCH_NOTIFICATION"));
            assertThatThrownBy(() -> statement.executeUpdate(
                    delivery(777, jobId, "MATCH_NOTIFICATION")))
                    .as("duplicate chat+job+type").isInstanceOf(java.sql.SQLException.class);

            // A different type and a different chat are both distinct deliveries.
            statement.executeUpdate(delivery(777, jobId, "REVIEW_DIGEST_ITEM"));
            statement.executeUpdate(delivery(888, jobId, "MATCH_NOTIFICATION"));

            assertThatThrownBy(() -> statement.executeUpdate(delivery(999, jobId, "EMAIL")))
                    .as("invalid delivery type").isInstanceOf(java.sql.SQLException.class);

            try (var rows = statement.executeQuery("SELECT COUNT(*) c FROM telegram_job_delivery")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isEqualTo(3);
            }
        }
    }

    @Test
    void deletingAJobRemovesItsWorkflowAndDeliveryRows() throws Exception {
        String url = migrate("v12cascade");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long jobId = seedJob(statement);
            statement.executeUpdate(workflow(jobId, "SAVED", "NULL", "NULL"));
            statement.executeUpdate(delivery(777, jobId, "MATCH_NOTIFICATION"));

            statement.executeUpdate("DELETE FROM jobs WHERE id = " + jobId);

            for (String table : new String[] {"job_workflow_state", "telegram_job_delivery"}) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) c FROM " + table)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt("c")).as(table).isZero();
                }
            }
        }
    }

    private String workflow(long jobId, String status, String note, String appliedAt) {
        return "INSERT INTO job_workflow_state (job_id, status, note, applied_at, created_at, updated_at) "
                + "VALUES (" + jobId + ", '" + status + "', " + note + ", " + appliedAt
                + ", CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    }

    private String delivery(long chatId, long jobId, String type) {
        return "INSERT INTO telegram_job_delivery (chat_id, job_id, delivery_type, delivered_at, created_at) "
                + "VALUES (" + chatId + ", " + jobId + ", '" + type
                + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    }
}
