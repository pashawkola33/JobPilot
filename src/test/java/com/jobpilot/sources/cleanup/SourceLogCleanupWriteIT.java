package com.jobpilot.sources.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.sources.cleanup.SourceLogCleanupProperties.Guards;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.DatabaseProof;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.SourceRow;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TableProof;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof for guarded selection, atomic rollback, preservation and replay safety. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "jobpilot.scheduled-tasks-enabled=false")
class SourceLogCleanupWriteIT {
    private static final String ERROR_SUMMARY =
            "PROCESS_INTERRUPTED: HistoricalOrphanReconciliation";
    private static final Instant OLD = Instant.parse("2020-01-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SourceLogCleanupReadRepository reads;
    @Autowired
    private SourceLogCleanupPreviewService preview;
    @Autowired
    private SourceLogCleanupWriteCoordinator writer;

    @BeforeEach
    void resetFixture() {
        removePartialUpdateTrigger();
        jdbc.update("DELETE FROM source_tenant_health");
        jdbc.update("DELETE FROM source_tenant_fetch_logs");
        jdbc.update("DELETE FROM source_fetch_logs");
    }

    @AfterEach
    void removeTrigger() {
        removePartialUpdateTrigger();
    }

    @Test
    void exactlySevenRowsTransitionAtomicallyAndReplayFailsClosed() {
        Fixture fixture = fixture();
        SourceLogCleanupPlan plan = preview.plan(guards(fixture.ids()));
        SourceLogCleanupProperties properties = writeProperties(plan);
        DatabaseProof before = reads.databaseProof();
        long sourceCountBefore = count("source_fetch_logs");
        Map<Long, SourceRow> originals = byId(reads.runningRows());
        Map<Long, Map<String, Object>> allBefore = allSourceRows();

        SourceLogCleanupWriteResult result = writer.execute(plan, properties);

        assertThat(result.status()).isEqualTo(SourceLogCleanupWriteResult.Status.SUCCESS);
        assertThat(result.rowsUpdated()).isEqualTo(7);
        assertThat(result.ids()).containsExactlyElementsOf(fixture.ids());
        List<Map<String, Object>> terminal = targetRows(fixture.ids());
        assertThat(terminal).hasSize(7).allSatisfy(row -> {
            long id = ((Number) row.get("id")).longValue();
            SourceRow original = originals.get(id);
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(((Timestamp) row.get("finished_at")).toInstant())
                    .isEqualTo(result.finishedAt());
            assertThat(((Number) row.get("fetched_count")).intValue())
                    .isEqualTo(original.fetchedCount());
            assertThat(((Number) row.get("saved_count")).intValue())
                    .isEqualTo(original.savedCount());
            assertThat(row.get("ingestion_run_id")).isEqualTo(original.ingestionRunId());
            assertThat(row.get("error_summary")).isEqualTo(ERROR_SUMMARY);
        });
        assertThat(terminal.stream().map(row -> row.get("finished_at")).distinct()).hasSize(1);
        assertThat(count("source_fetch_logs")).isEqualTo(sourceCountBefore);
        assertThat(countRunning()).isZero();
        Map<Long, Map<String, Object>> allAfter = allSourceRows();
        assertThat(allAfter.keySet()).isEqualTo(allBefore.keySet());
        assertThat(allAfter.keySet().stream()
                .filter(id -> !allAfter.get(id).equals(allBefore.get(id))).toList())
                .containsExactlyElementsOf(fixture.ids());

        DatabaseProof after = reads.databaseProof();
        assertProtectedProof(before, after);
        SourceLogCleanupPlan empty = preview.plan(
                new Guards(Duration.ofHours(6), 20, List.of(), 0));
        assertThat(empty.previewSafe()).isTrue();
        assertThat(empty.observedRunningIds()).isEmpty();
        assertThat(empty.entries()).isEmpty();
        assertThat(empty.futureWriteEligible()).isFalse();
        assertThat(empty.fingerprint()).isNotEqualTo(plan.fingerprint());

        DatabaseProof beforeReplay = reads.databaseProof();
        SourceLogCleanupWriteResult staleFingerprint = writer.execute(empty, properties);
        assertThat(staleFingerprint.status()).isEqualTo(SourceLogCleanupWriteResult.Status.ERROR);
        assertThat(staleFingerprint.rowsUpdated()).isZero();
        SourceLogCleanupWriteResult replay = writer.execute(plan, properties);
        assertThat(replay.status()).isEqualTo(SourceLogCleanupWriteResult.Status.ERROR);
        assertThat(replay.rowsUpdated()).isZero();
        assertThat(reads.databaseProof()).isEqualTo(beforeReplay);
    }

    @Test
    void rowChangedAfterPlanningAbortsBeforeAnyCleanupUpdate() {
        Fixture fixture = fixture();
        SourceLogCleanupPlan plan = preview.plan(guards(fixture.ids()));
        long changedId = fixture.ids().getLast();
        jdbc.update("UPDATE source_fetch_logs SET fetched_count = fetched_count + 1 WHERE id = ?",
                changedId);

        SourceLogCleanupWriteResult result = writer.execute(plan, writeProperties(plan));

        assertThat(result.status()).isEqualTo(SourceLogCleanupWriteResult.Status.ERROR);
        assertThat(countRunning()).isEqualTo(7);
        assertThat(targetRows(fixture.ids())).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("RUNNING");
            assertThat(row.get("finished_at")).isNull();
            assertThat(row.get("error_summary")).isNull();
        });
    }

    @Test
    void conditionalPartialUpdateRollsBackEarlierRows() {
        Fixture fixture = fixture();
        SourceLogCleanupPlan plan = preview.plan(guards(fixture.ids()));
        installPartialUpdateTrigger(fixture.ids().get(2));

        SourceLogCleanupWriteResult result = writer.execute(plan, writeProperties(plan));

        assertThat(result.status()).isEqualTo(SourceLogCleanupWriteResult.Status.ERROR);
        assertThat(countRunning()).isEqualTo(7);
        assertThat(targetRows(fixture.ids())).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("RUNNING");
            assertThat(row.get("finished_at")).isNull();
            assertThat(row.get("error_summary")).isNull();
        });
    }

    private Fixture fixture() {
        ArrayList<Long> ids = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            UUID runId = UUID.randomUUID();
            String source = "cleanup-it-source-" + index;
            Long id = jdbc.queryForObject("""
                    INSERT INTO source_fetch_logs
                        (ingestion_run_id, source_name, started_at, status,
                         fetched_count, saved_count)
                    VALUES (?, ?, ?, 'RUNNING', ?, ?)
                    RETURNING id
                    """, Long.class, runId, source, Timestamp.from(OLD.plusSeconds(index)),
                    10 + index, 3 + index);
            ids.add(id);
            jdbc.update("""
                    INSERT INTO source_tenant_fetch_logs
                        (ingestion_run_id, provider, tenant, attempt_status,
                         started_at, finished_at)
                    VALUES (?, 'workday', ?, 'SUCCESS', ?, ?)
                    """, runId, "tenant-" + index, Timestamp.from(OLD.plusSeconds(index)),
                    Timestamp.from(OLD.plusSeconds(index + 1L)));
            jdbc.update("""
                    INSERT INTO source_fetch_logs
                        (ingestion_run_id, source_name, started_at, finished_at, status,
                         fetched_count, saved_count)
                    VALUES (?, ?, ?, ?, 'SUCCESS', 1, 1)
                    """, UUID.randomUUID(), source, Timestamp.from(OLD.plusSeconds(100 + index)),
                    Timestamp.from(OLD.plusSeconds(101 + index)));
        }
        jdbc.update("""
                INSERT INTO source_tenant_health
                    (provider, tenant, last_attempt_status, last_failure_category,
                     last_fetched_count, last_duration_ms, consecutive_failures,
                     total_attempts, total_successes, total_failures,
                     last_attempt_at, last_success_at, created_at, updated_at)
                VALUES ('workday', 'protected-health', 'SUCCESS', 'NONE', 1, 1, 0,
                        1, 1, 0, ?, ?, ?, ?)
                """, Timestamp.from(OLD), Timestamp.from(OLD), Timestamp.from(OLD),
                Timestamp.from(OLD));
        return new Fixture(List.copyOf(ids));
    }

    private Guards guards(List<Long> ids) {
        return new Guards(Duration.ofHours(6), 20, ids, ids.size());
    }

    private SourceLogCleanupProperties writeProperties(SourceLogCleanupPlan plan) {
        String ids = plan.expectedRunningIds().stream().map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse("");
        return new SourceLogCleanupProperties(SourceLogCleanupProperties.Mode.WRITE, true,
                Duration.ofHours(6), 20, ids, "7", plan.fingerprint(),
                SourceLogCleanupWriteGuards.CONFIRMATION);
    }

    private Map<Long, SourceRow> byId(List<SourceRow> rows) {
        LinkedHashMap<Long, SourceRow> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.id(), row));
        return result;
    }

    private List<Map<String, Object>> targetRows(List<Long> ids) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.queryForList("""
                SELECT id, ingestion_run_id, status, started_at, finished_at,
                       fetched_count, saved_count, error_summary
                  FROM source_fetch_logs
                 WHERE id IN (%s)
                 ORDER BY id
                """.formatted(placeholders), ids.toArray());
    }

    private Map<Long, Map<String, Object>> allSourceRows() {
        LinkedHashMap<Long, Map<String, Object>> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT id, ingestion_run_id, source_name, started_at, finished_at, status,
                       fetched_count, saved_count, error_summary
                  FROM source_fetch_logs
                 ORDER BY id
                """).forEach(row -> result.put(((Number) row.get("id")).longValue(), row));
        return result;
    }

    private long countRunning() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_fetch_logs WHERE status = 'RUNNING'", Long.class);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void assertProtectedProof(DatabaseProof before, DatabaseProof after) {
        Map<String, TableProof> beforeByTable = before.tables().stream()
                .collect(java.util.stream.Collectors.toMap(TableProof::table, proof -> proof));
        Map<String, TableProof> afterByTable = after.tables().stream()
                .collect(java.util.stream.Collectors.toMap(TableProof::table, proof -> proof));
        assertThat(afterByTable.get("source_fetch_logs").rowCount())
                .isEqualTo(beforeByTable.get("source_fetch_logs").rowCount());
        assertThat(afterByTable.get("source_fetch_logs").fingerprint())
                .isNotEqualTo(beforeByTable.get("source_fetch_logs").fingerprint());
        assertThat(after.tables().stream()
                .filter(proof -> !"source_fetch_logs".equals(proof.table())).toList())
                .isEqualTo(before.tables().stream()
                        .filter(proof -> !"source_fetch_logs".equals(proof.table())).toList());
    }

    private void installPartialUpdateTrigger(long blockedId) {
        jdbc.execute("""
                CREATE FUNCTION source_cleanup_skip_test_update() RETURNS trigger
                LANGUAGE plpgsql AS $function$
                BEGIN
                    IF NEW.id = %d AND NEW.status = 'FAILED' THEN
                        RETURN NULL;
                    END IF;
                    RETURN NEW;
                END
                $function$
                """.formatted(blockedId));
        jdbc.execute("""
                CREATE TRIGGER source_cleanup_skip_test_trigger
                BEFORE UPDATE ON source_fetch_logs
                FOR EACH ROW EXECUTE FUNCTION source_cleanup_skip_test_update()
                """);
    }

    private void removePartialUpdateTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS source_cleanup_skip_test_trigger ON source_fetch_logs");
        jdbc.execute("DROP FUNCTION IF EXISTS source_cleanup_skip_test_update()");
    }

    private record Fixture(List<Long> ids) {
    }
}
