package com.jobpilot.miniapp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;

import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppApplication;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppJob;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Mini App durability contract against real PostgreSQL. All jobs and credentials are
 * synthetic, scheduling and Telegram delivery remain disabled, and every test starts empty.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MiniAppDurabilityIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MiniAppWorkflowService workflows;

    @Autowired
    private MiniAppSnapshotService snapshots;

    @SpyBean
    private ApplicationTrackerService applications;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from application_status_history");
        jdbc.update("delete from applications");
        jdbc.update("delete from telegram_job_delivery");
        jdbc.update("delete from job_workflow_state");
        jdbc.update("delete from job_scores");
        jdbc.update("delete from jobs");
    }

    @Test
    void saveSurvivesReloadAndASecondContext() {
        long jobId = active("saved", 91);

        MiniAppWorkflowResult response = workflows.change(jobId, WorkflowStatus.SAVED);
        var reloaded = snapshots.snapshot();
        var secondContext = snapshots.snapshot();

        assertThat(response.workflow().status()).isEqualTo(WorkflowStatus.SAVED);
        assertThat(response.snapshot().saved().items())
                .extracting(MiniAppJob::id).containsExactly(jobId);
        assertThat(reloaded.saved().items()).extracting(MiniAppJob::id).containsExactly(jobId);
        assertThat(secondContext.saved().items()).extracting(MiniAppJob::id).containsExactly(jobId);
        assertThat(reloaded.applications().items())
                .extracting(MiniAppApplication::jobId).containsExactly(jobId);
        assertThat(reloaded.workflowCounts().saved()).isEqualTo(1);
        assertThat(reloaded.applicationCounts().saved()).isEqualTo(1);
    }

    @Test
    void appliedSurvivesReloadAndASecondContext() {
        long jobId = active("applied", 88);

        workflows.change(jobId, WorkflowStatus.APPLIED);
        var reloaded = snapshots.snapshot();
        var secondContext = snapshots.snapshot();

        assertThat(reloaded.applications().items()).singleElement().satisfies(application -> {
            assertThat(application.jobId()).isEqualTo(jobId);
            assertThat(application.status()).isEqualTo(ApplicationStatus.APPLIED);
            assertThat(application.appliedAt()).isNotNull();
        });
        assertThat(secondContext.applications().items())
                .extracting(MiniAppApplication::jobId).containsExactly(jobId);
        assertThat(reloaded.workflowCounts().applied()).isEqualTo(1);
        assertThat(reloaded.applicationCounts().applied()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {49, 50, 51})
    void reviewQueueReportsItsAuthoritativeBoundary(int unreviewed) {
        IntStream.range(0, unreviewed).forEach(index -> active("queue-" + index, 80));

        var page = snapshots.snapshot().reviewQueue();

        assertThat(page.items()).hasSize(Math.min(unreviewed, MiniAppSnapshotService.MAX_REVIEW_JOBS));
        assertThat(page.total()).isEqualTo(unreviewed);
        assertThat(page.limit()).isEqualTo(MiniAppSnapshotService.MAX_REVIEW_JOBS);
        assertThat(page.truncated()).isEqualTo(unreviewed > MiniAppSnapshotService.MAX_REVIEW_JOBS);
    }

    @Test
    void savedDoesNotCompeteWithFiftyOneUnreviewedVacancies() {
        long saved = active("durable-saved", 1);
        workflows.change(saved, WorkflowStatus.SAVED);
        IntStream.range(0, 51).forEach(index -> active("unreviewed-" + index, 100 - index));

        var snapshot = snapshots.snapshot();

        assertThat(snapshot.reviewQueue().items()).hasSize(MiniAppSnapshotService.MAX_REVIEW_JOBS);
        assertThat(snapshot.reviewQueue().total()).isEqualTo(51);
        assertThat(snapshot.saved().items()).extracting(MiniAppJob::id).containsExactly(saved);
        assertThat(snapshot.saved().total()).isEqualTo(1);
        assertThat(snapshot.workflowCounts().saved()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 20, 21})
    void applicationsReportTheirAuthoritativeBoundary(int tracked) {
        IntStream.range(0, tracked).forEach(index -> applications.transition(
                active("application-" + index, 70), ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.INTERNAL));

        var snapshot = snapshots.snapshot();

        assertThat(snapshot.applications().items())
                .hasSize(Math.min(tracked, MiniAppSnapshotService.MAX_APPLICATIONS));
        assertThat(snapshot.applications().total()).isEqualTo(tracked);
        assertThat(snapshot.applications().limit()).isEqualTo(MiniAppSnapshotService.MAX_APPLICATIONS);
        assertThat(snapshot.applications().truncated())
                .isEqualTo(tracked > MiniAppSnapshotService.MAX_APPLICATIONS);
        assertThat(snapshot.applicationCounts().total()).isEqualTo(tracked);
        assertThat(snapshot.applicationCounts().saved()).isEqualTo(tracked);
    }

    @Test
    void repeatedSavedAndAppliedCommandsAreIdempotent() {
        long jobId = active("idempotent", 84);

        assertThat(workflows.change(jobId, WorkflowStatus.SAVED).changed()).isTrue();
        assertThat(workflows.change(jobId, WorkflowStatus.SAVED).changed()).isFalse();
        assertThat(workflows.change(jobId, WorkflowStatus.APPLIED).changed()).isTrue();
        assertThat(workflows.change(jobId, WorkflowStatus.APPLIED).changed()).isFalse();

        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(count("job_workflow_state", "job_id", jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from application_status_history h
                join applications a on a.id = h.application_id
                where a.job_id = ?
                """, Integer.class, jobId)).isEqualTo(2);
    }

    /**
     * Coarse atomicity only: an arbitrary tracker failure abandons the workflow change with it.
     * Conflict behaviour is a separate question and lives in MiniAppConflictIT, which injects
     * from inside the transaction instead of stubbing the call away.
     */
    @Test
    void applicationFailureRollsBackTheWorkflowChange() {
        long jobId = active("atomic", 82);
        doThrow(new IllegalStateException("synthetic application persistence failure"))
                .when(applications).transitionInCurrentTransaction(eq(jobId),
                        eq(ApplicationStatus.SAVED), isNull(Instant.class), isNull(String.class),
                        eq(ApplicationStatusChangeSource.INTERNAL));

        assertThatThrownBy(() -> workflows.change(jobId, WorkflowStatus.SAVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic application persistence failure");

        assertThat(count("job_workflow_state", "job_id", jobId)).isZero();
        assertThat(count("applications", "job_id", jobId)).isZero();
    }

    @Test
    void applyingPreservesExistingApplicationHistory() {
        long jobId = active("history", 79);
        applications.transition(jobId, ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.INTERNAL);

        workflows.change(jobId, WorkflowStatus.APPLIED);

        assertThat(jdbc.queryForList("""
                select h.new_status from application_status_history h
                join applications a on a.id = h.application_id
                where a.job_id = ? order by h.changed_at, h.id
                """, String.class, jobId)).containsExactly("SAVED", "APPLIED");
    }

    @Test
    void resetNeverDeletesAPreExistingTrackedApplication() {
        long jobId = active("undo-boundary", 77);
        workflows.change(jobId, WorkflowStatus.SAVED);
        int historyBeforeReset = jdbc.queryForObject("""
                select count(*) from application_status_history h
                join applications a on a.id = h.application_id
                where a.job_id = ?
                """, Integer.class, jobId);

        workflows.change(jobId, WorkflowStatus.UNREVIEWED);

        assertThat(count("job_workflow_state", "job_id", jobId)).isZero();
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from application_status_history h
                join applications a on a.id = h.application_id
                where a.job_id = ?
                """, Integer.class, jobId)).isEqualTo(historyBeforeReset);
    }

    private long active(String externalId, int score) {
        jdbc.update("""
                insert into jobs (source, provider_tenant, external_id, canonical_url, title,
                                  company, location, description, status, screening_disposition,
                                  fetched_at, first_seen_at, last_seen_at, published_at,
                                  raw_payload_hash, description_hash, normalized_fingerprint)
                values ('greenhouse', 'synthetic', ?, ?, ?, 'Synthetic Company', 'Bucharest',
                        'Synthetic description', 'NEW', ?, now(), now(), now(), now(), ?, ?, ?)
                """, externalId, "https://example.test/jobs/" + externalId,
                "Synthetic vacancy " + externalId, ScreeningDisposition.MATCH.name(),
                externalId, externalId, externalId);
        Long id = jdbc.queryForObject(
                "select id from jobs where provider_tenant = 'synthetic' and external_id = ?",
                Long.class, externalId);
        jdbc.update("""
                insert into job_scores (job_id, score, band, suitable, formal_eligibility,
                                        java_backend, trainee_quality, supporting_technology,
                                        location_format, experience_compatibility, freshness,
                                        penalties, strengths, risks, hard_blockers, scored_at)
                values (?, ?, 'GOOD_MATCH', true, 1, 1, 1, 1, 1, 1, 1, 0, '', '', '', now())
                """, id, Math.clamp(score, 0, 100));
        return id;
    }

    private int count(String table, String column, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Integer.class, id);
    }
}
