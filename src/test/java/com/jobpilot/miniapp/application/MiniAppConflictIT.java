package com.jobpilot.miniapp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.application.ApplicationTrackingException;
import com.jobpilot.applications.application.ApplicationTransitionPolicy;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.AssertionFailure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.UnexpectedRollbackException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Conflict behaviour of the Mini App mutation against real PostgreSQL.
 *
 * <p>A retry is only a retry if the next attempt can still commit. These tests inject from
 * <em>inside</em> the tracker's transaction — the boundary a stubbed-away
 * {@code transition(...)} never reaches — and assert that the Mini App path recovers, that an
 * exhausted retry becomes a typed CONFLICT, and that neither UnexpectedRollbackException nor
 * Hibernate's AssertionFailure can escape.
 *
 * <p>All jobs and credentials are synthetic and every test starts from an empty schema.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MiniAppConflictIT {
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
    private ApplicationTrackerService tracker;

    /** A concrete collaborator the tracker consults inside its transaction. */
    @SpyBean
    private ApplicationTransitionPolicy transitionPolicy;

    @SpyBean
    private MiniAppSnapshotService snapshots;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        reset(transitionPolicy, snapshots);
        jdbc.update("delete from application_status_history");
        jdbc.update("delete from applications");
        jdbc.update("delete from telegram_job_delivery");
        jdbc.update("delete from job_workflow_state");
        jdbc.update("delete from job_scores");
        jdbc.update("delete from jobs");
    }

    /** Fails the first {@code failures} attempts from inside whichever transaction is current. */
    private AtomicInteger failFirst(int failures) {
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() <= failures) {
                throw new OptimisticLockingFailureException("injected conflict");
            }
            return call.callRealMethod();
        }).when(transitionPolicy).canCreate(any());
        return attempts;
    }

    @Test
    void standaloneTrackerRetriesThroughAConflict() {
        long jobId = active("standalone", 80);
        AtomicInteger attempts = failFirst(1);

        var result = tracker.transition(jobId, ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.INTERNAL);

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(result.changed()).isTrue();
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
    }

    @Test
    void miniAppRetriesInAFreshTransactionAndLeavesNoPartialState() {
        long jobId = active("fresh-transaction", 80);
        AtomicInteger attempts = failFirst(1);

        var result = workflows.change(jobId, WorkflowStatus.SAVED);

        assertThat(attempts.get()).as("the first attempt failed and a second one ran").isEqualTo(2);
        assertThat(result.workflow().status()).isEqualTo(WorkflowStatus.SAVED);
        // The abandoned attempt left nothing behind and the surviving one committed both halves.
        assertThat(count("job_workflow_state", "job_id", jobId)).isEqualTo(1);
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(historyRows(jobId)).isEqualTo(1);
        assertThat(result.snapshot().saved().items()).hasSize(1);
    }

    @Test
    void exhaustedMiniAppRetriesRaiseATypedConflict() {
        long jobId = active("exhausted", 80);
        AtomicInteger attempts = failFirst(ApplicationTrackerService.MAX_CONFLICT_ATTEMPTS);

        Throwable thrown = org.assertj.core.api.Assertions
                .catchThrowable(() -> workflows.change(jobId, WorkflowStatus.SAVED));

        assertThat(attempts.get()).isEqualTo(ApplicationTrackerService.MAX_CONFLICT_ATTEMPTS);
        assertThat(thrown)
                .isInstanceOf(ApplicationTrackingException.class)
                .isNotInstanceOf(UnexpectedRollbackException.class)
                .isNotInstanceOf(AssertionFailure.class);
        assertThat(((ApplicationTrackingException) thrown).getCategory())
                .isEqualTo(ApplicationTrackingException.Category.CONFLICT);
        // Nothing partial survives an exhausted retry.
        assertThat(count("job_workflow_state", "job_id", jobId)).isZero();
        assertThat(count("applications", "job_id", jobId)).isZero();
    }

    /**
     * The real cross-path race: a Mini App save against a direct tracker save (the Telegram bot
     * and ApplicationController path, which takes no {@code jobs} row lock). The latch removes
     * the timing luck — the Mini App attempt is parked until the bot has committed, so its
     * insert is guaranteed to hit the {@code applications.job_id} unique index.
     */
    @Test
    void aRealDuplicateKeyRaceLeavesExactlyOneConsistentApplication() throws Exception {
        long jobId = active("race", 80);
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger miniAppAttempts = new AtomicInteger();

        doAnswer(call -> {
            // Park only the first Mini App attempt; the retry must run unimpeded.
            if (Thread.currentThread().getName().equals("mini-app")
                    && miniAppAttempts.incrementAndGet() == 1) {
                parked.countDown();
                released.await(30, TimeUnit.SECONDS);
            }
            return call.callRealMethod();
        }).when(transitionPolicy).canCreate(any());

        AtomicReference<Throwable> miniAppFailure = new AtomicReference<>();
        Thread miniApp = new Thread(() -> {
            try {
                workflows.change(jobId, WorkflowStatus.SAVED);
            } catch (Throwable failure) {
                miniAppFailure.set(failure);
            }
        }, "mini-app");
        miniApp.start();

        assertThat(parked.await(30, TimeUnit.SECONDS)).isTrue();
        tracker.transition(jobId, ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.INTERNAL);
        released.countDown();
        miniApp.join(60_000);

        // The first attempt loses the insert race; the second runs in a fresh transaction, reads
        // the row the bot committed, and settles as a no-op. Before the fix it died inside a
        // rollback-only transaction instead.
        assertThat(miniAppFailure.get())
                .as("the retry recovers from a real duplicate key")
                .isNull();
        assertThat(miniAppAttempts.get()).isGreaterThanOrEqualTo(1);
        // Exactly one application, one history row, and both halves of the workflow consistent.
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(historyRows(jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from applications where job_id = ?",
                String.class, jobId)).isEqualTo("SAVED");
        assertThat(count("job_workflow_state", "job_id", jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from job_workflow_state where job_id = ?",
                String.class, jobId)).isEqualTo("SAVED");
    }

    @Test
    void snapshotFailureRollsBackBothHalvesOfTheMutation() {
        long jobId = active("snapshot-failure", 80);
        doThrow(new IllegalStateException("synthetic snapshot failure")).when(snapshots).snapshot();

        assertThatThrownBy(() -> workflows.change(jobId, WorkflowStatus.SAVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic snapshot failure");

        assertThat(count("job_workflow_state", "job_id", jobId)).isZero();
        assertThat(count("applications", "job_id", jobId)).isZero();
        assertThat(historyRows(jobId)).isZero();
    }

    @Test
    void transitionInCurrentTransactionRefusesToRunWithoutOne() {
        long jobId = active("no-transaction", 80);

        assertThatThrownBy(() -> tracker.transitionInCurrentTransaction(jobId,
                ApplicationStatus.SAVED, null, null, ApplicationStatusChangeSource.INTERNAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires an active transaction");

        assertThat(count("applications", "job_id", jobId)).isZero();
    }

    private int historyRows(long jobId) {
        return jdbc.queryForObject("""
                select count(*) from application_status_history h
                join applications a on a.id = h.application_id
                where a.job_id = ?
                """, Integer.class, jobId);
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
