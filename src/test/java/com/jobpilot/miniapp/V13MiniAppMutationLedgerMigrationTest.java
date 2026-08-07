package com.jobpilot.miniapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V13 on H2 in PostgreSQL mode, like the V8, V9 and V12 migration tests. No container, no
 * network, no live database.
 *
 * <p>These constraints are the ones carrying the P0-B invariants into the schema, so they are
 * asserted rather than assumed: a ledger that silently accepted two rows for one mutation key,
 * or a live token on a spent mutation, would break idempotency and Undo without failing loudly
 * anywhere else.
 */
class V13MiniAppMutationLedgerMigrationTest {
    private String migrate(String name) {
        String url = "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        return url;
    }

    private long seedJob(Statement statement) throws Exception {
        statement.executeUpdate("""
                INSERT INTO jobs (source, provider_tenant, external_id, canonical_url, title,
                                  company, location, description, status, fetched_at,
                                  first_seen_at, last_seen_at, raw_payload_hash, description_hash,
                                  normalized_fingerprint)
                VALUES ('greenhouse', 'acme', 'ext-1', 'https://example.test/jobs/1', 'Java Intern',
                        'Acme', 'Bucharest', 'Description', 'NEW', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'h1', 'h2', 'h3')
                """);
        try (var rows = statement.executeQuery("SELECT id FROM jobs WHERE external_id = 'ext-1'")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong("id");
        }
    }

    @Test
    void appliesVersionThirteenAndSeedsTheSingletonRevisionRow() throws Exception {
        String url = migrate("v13apply");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) c FROM flyway_schema_history "
                            + "WHERE version = '13' AND success = TRUE")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isEqualTo(1);
            }
            // The counter must exist before the first mutation: the mutation path locks this row
            // as its first statement and has nothing to lock if the migration did not seed it.
            try (var rows = statement.executeQuery(
                    "SELECT id, mutation_revision FROM mini_app_state")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("id")).isEqualTo(1);
                assertThat(rows.getLong("mutation_revision")).isZero();
                assertThat(rows.next()).as("exactly one row").isFalse();
            }
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO mini_app_state (id, mutation_revision) VALUES (2, 0)"))
                    .as("singleton").isInstanceOf(SQLException.class);
        }
    }

    /**
     * The workflow-side half of the Undo fingerprint. Without it, a Telegram note — which changes
     * neither the workflow status nor any application row — would be invisible to the freshness
     * check, and reversing would restore the old note over the newer one.
     */
    @Test
    void addsAWorkflowVersionAndBackfillsExistingRows() throws Exception {
        String url = migrate("v13workflowversion");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long jobId = seedJob(statement);
            statement.executeUpdate("""
                    INSERT INTO job_workflow_state (job_id, status, note, applied_at,
                                                    created_at, updated_at)
                    VALUES (%d, 'SAVED', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """.formatted(jobId));

            // Rows V12 created carry 0 rather than NULL, so Hibernate can maintain it from there.
            try (var rows = statement.executeQuery(
                    "SELECT version FROM job_workflow_state WHERE job_id = " + jobId)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("version")).isZero();
            }
        }
    }

    @Test
    void keepsTheWorkflowStatusAndVersionFingerprintTogether() throws Exception {
        String url = migrate("v13fingerprint");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long jobId = seedJob(statement);

            // Half a fingerprint would leave the freshness check blind in the one case it exists
            // for, so the schema refuses to record one.
            assertThatThrownBy(() -> statement.executeUpdate(
                    fingerprint("half-1", jobId, 1, "'SAVED'", "NULL")))
                    .as("status without version").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    fingerprint("half-2", jobId, 2, "NULL", "3")))
                    .as("version without status").isInstanceOf(SQLException.class);

            statement.executeUpdate(fingerprint("whole", jobId, 3, "'SAVED'", "3"));
            // A mutation that left the job UNREVIEWED records neither.
            statement.executeUpdate(fingerprint("neither", jobId, 4, "NULL", "NULL"));
        }
    }

    @Test
    void enforcesIdempotencyAndRevisionUniqueness() throws Exception {
        String url = migrate("v13unique");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long jobId = seedJob(statement);
            statement.executeUpdate(mutation("key-1", jobId, 1, "REVERSIBLE", "'token-1'"));

            // The idempotency guarantee is the database's, not the application's.
            assertThatThrownBy(() -> statement.executeUpdate(
                    mutation("key-1", jobId, 2, "REVERSIBLE", "'token-2'")))
                    .as("duplicate mutation key").isInstanceOf(SQLException.class);
            // Two mutations cannot share a revision, so revision order is a total order.
            assertThatThrownBy(() -> statement.executeUpdate(
                    mutation("key-2", jobId, 1, "REVERSIBLE", "'token-3'")))
                    .as("duplicate revision").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    mutation("key-3", jobId, 3, "REVERSIBLE", "'token-1'")))
                    .as("duplicate undo token").isInstanceOf(SQLException.class);
        }
    }

    @Test
    void couplesTheUndoTokenAndReversedByToTheReversalState() throws Exception {
        String url = migrate("v13state");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long jobId = seedJob(statement);

            // A mutation that changed nothing is never reversible and carries no token.
            assertThatThrownBy(() -> statement.executeUpdate(
                    mutation("bad-1", jobId, 1, "NOT_REVERSIBLE", "'token-x'")))
                    .as("token on a NOT_REVERSIBLE row").isInstanceOf(SQLException.class);
            // Everything that was once reversible keeps its token, so a replay can identify it.
            assertThatThrownBy(() -> statement.executeUpdate(
                    mutation("bad-2", jobId, 2, "SUPERSEDED", "NULL")))
                    .as("superseded without its token").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    mutation("bad-3", jobId, 3, "NONSENSE", "'token-y'")))
                    .as("unknown reversal state").isInstanceOf(SQLException.class);
            // REVERSED must name the reversal that consumed it; nothing else may.
            assertThatThrownBy(() -> statement.executeUpdate(
                    mutation("bad-4", jobId, 4, "REVERSED", "'token-z'")))
                    .as("REVERSED without reversed_by").isInstanceOf(SQLException.class);

            statement.executeUpdate(mutation("ok-1", jobId, 5, "NOT_REVERSIBLE", "NULL"));
            statement.executeUpdate(mutation("ok-2", jobId, 6, "SUPERSEDED", "'token-kept'"));
        }
    }

    @Test
    void deletingAJobRemovesItsLedgerRows() throws Exception {
        String url = migrate("v13cascade");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long jobId = seedJob(statement);
            statement.executeUpdate(mutation("key-1", jobId, 1, "REVERSIBLE", "'token-1'"));

            statement.executeUpdate("DELETE FROM jobs WHERE id = " + jobId);

            try (var rows = statement.executeQuery("SELECT COUNT(*) c FROM mini_app_mutation")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isZero();
            }
        }
    }

    private String fingerprint(String key, long jobId, long revision, String status, String version) {
        return "INSERT INTO mini_app_mutation (mutation_key, job_id, kind, requested_status, "
                + "mutation_revision, changed, created_application, reversal_state, undo_token, "
                + "resulting_workflow_status, resulting_workflow_version, created_at) VALUES ('"
                + key + "', " + jobId + ", 'WORKFLOW', 'SAVED', " + revision
                + ", TRUE, FALSE, 'REVERSIBLE', 'token-" + key + "', " + status + ", " + version
                + ", CURRENT_TIMESTAMP)";
    }

    private String mutation(String key, long jobId, long revision, String state, String token) {
        return "INSERT INTO mini_app_mutation (mutation_key, job_id, kind, requested_status, "
                + "mutation_revision, changed, created_application, reversal_state, undo_token, "
                + "created_at) VALUES ('" + key + "', " + jobId + ", 'WORKFLOW', 'SAVED', "
                + revision + ", TRUE, FALSE, '" + state + "', " + token + ", CURRENT_TIMESTAMP)";
    }
}
