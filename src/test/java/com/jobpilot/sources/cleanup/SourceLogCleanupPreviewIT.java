package com.jobpilot.sources.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.sources.cleanup.SourceLogCleanupProperties.Guards;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.DatabaseProof;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof that PREVIEW executes in read-only repeatable-read and mutates no table. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "jobpilot.scheduled-tasks-enabled=false")
class SourceLogCleanupPreviewIT {
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

    @Test
    void previewLeavesEveryProtectedTableUnchanged() {
        UUID runId = UUID.randomUUID();
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        Long runningId = jdbc.queryForObject("""
                INSERT INTO source_fetch_logs
                    (ingestion_run_id, source_name, started_at, status,
                     fetched_count, saved_count)
                VALUES (?, 'preview-it-source', ?, 'RUNNING', 0, 0)
                RETURNING id
                """, Long.class, runId, Timestamp.from(old));
        jdbc.update("""
                INSERT INTO source_tenant_fetch_logs
                    (ingestion_run_id, provider, tenant, attempt_status,
                     started_at, finished_at)
                VALUES (?, 'workday', 'test/site', 'SUCCESS', ?, ?)
                """, runId, Timestamp.from(old), Timestamp.from(old.plusSeconds(1)));
        jdbc.update("""
                INSERT INTO source_fetch_logs
                    (ingestion_run_id, source_name, started_at, finished_at, status,
                     fetched_count, saved_count)
                VALUES (?, 'preview-it-source', ?, ?, 'SUCCESS', 1, 1)
                """, UUID.randomUUID(), Timestamp.from(old.plusSeconds(10)),
                Timestamp.from(old.plusSeconds(11)));
        DatabaseProof before = reads.databaseProof();

        SourceLogCleanupPlan plan = preview.plan(new Guards(
                Duration.ofHours(6), 20, List.of(runningId), 1));

        DatabaseProof after = reads.databaseProof();
        assertThat(plan.transactionMode().safe()).isTrue();
        assertThat(plan.futureWriteEligible()).isTrue();
        assertThat(plan.proofBefore()).isEqualTo(plan.proofAfter());
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForMap("""
                SELECT status, finished_at, fetched_count, saved_count, error_summary
                  FROM source_fetch_logs WHERE id = ?
                """, runningId)).containsEntry("status", "RUNNING")
                .containsEntry("finished_at", null)
                .containsEntry("fetched_count", 0)
                .containsEntry("saved_count", 0)
                .containsEntry("error_summary", null);
    }
}
