package com.jobpilot.miniapp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.application.ApplicationTransitionPolicy;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.jobreview.application.JobReviewService;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobreview.repository.JobReviewQueryRepository;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.miniapp.api.MiniAppSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The P0-B consistency contract against real PostgreSQL.
 *
 * <p>Every race here is forced with latches and barriers, never with sleeps: a test that passes
 * because a thread happened to be slow proves nothing and fails on someone else's machine. Each
 * test names the invariant from docs/mini-app-p0b-consistency-model.md that it holds down.
 *
 * <p>All jobs and credentials are synthetic and every test starts from an empty schema.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MiniAppConsistencyIT {
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

    @Autowired
    private ApplicationTrackerService tracker;

    /** The Telegram command path's own collaborator, used here as the external writer. */
    @Autowired
    private JobReviewService review;

    /** A concrete collaborator consulted from inside the mutation transaction — the park point. */
    @SpyBean
    private ApplicationTransitionPolicy transitionPolicy;

    /** The first statement of the snapshot read, used to observe its real isolation. */
    @SpyBean
    private JobReviewQueryRepository queries;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        reset(transitionPolicy, queries);
        jdbc.update("delete from mini_app_mutation");
        jdbc.update("update mini_app_state set mutation_revision = 0");
        jdbc.update("delete from application_status_history");
        jdbc.update("delete from applications");
        jdbc.update("delete from telegram_job_delivery");
        jdbc.update("delete from job_workflow_state");
        jdbc.update("delete from job_scores");
        jdbc.update("delete from jobs");
    }

    // ------------------------------------------------------------------- I1/I4 ordering

    @Test
    void saveThenApplyOnOneJobSettlesAsApplied() {
        long jobId = active("save-then-apply", 90);

        var saved = change(jobId, WorkflowStatus.SAVED);
        var applied = change(jobId, WorkflowStatus.APPLIED);

        assertThat(saved.mutationRevision()).isEqualTo(1);
        assertThat(applied.mutationRevision()).isEqualTo(2);
        assertThat(applied.status()).isEqualTo(WorkflowStatus.APPLIED);
        assertThat(applied.applicationStatus()).isEqualTo(ApplicationStatus.APPLIED);
        assertThat(workflowStatus(jobId)).isEqualTo("APPLIED");
    }

    /**
     * I4. The mutation that <em>starts</em> first also commits first, because it takes the
     * revision row lock as its first statement and holds it to commit.
     *
     * <p>The proof is the middle assertion: while the first mutation is parked, the second is
     * blocked and no revision has been published at all. A sequence would have handed the second
     * transaction a number already — this shows revision order really is commit order.
     */
    @Test
    void revisionOrderIsCommitOrderNotTransactionStartOrder() throws Exception {
        long first = active("commit-order-a", 90);
        long second = active("commit-order-b", 80);
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        parkThread("first", parked, release);

        AtomicReference<MiniAppOperation> firstResult = new AtomicReference<>();
        Thread firstThread = new Thread(() ->
                firstResult.set(change(first, WorkflowStatus.SAVED)), "first");
        firstThread.start();
        assertThat(parked.await(30, TimeUnit.SECONDS)).isTrue();

        AtomicReference<MiniAppOperation> secondResult = new AtomicReference<>();
        Thread secondThread = new Thread(() ->
                secondResult.set(change(second, WorkflowStatus.SAVED)), "second");
        secondThread.start();
        // The second mutation is now blocked on the revision row. Give it room to prove it stays
        // blocked: nothing it could do would let it commit while the lock is held.
        secondThread.join(2_000);
        assertThat(secondThread.isAlive()).as("the second mutation waits for the lock").isTrue();
        assertThat(committedRevision()).as("no revision is published while the first is open")
                .isZero();

        release.countDown();
        firstThread.join(30_000);
        secondThread.join(30_000);

        assertThat(firstResult.get().mutationRevision()).isEqualTo(1);
        assertThat(secondResult.get().mutationRevision()).isEqualTo(2);
        assertThat(committedRevision()).isEqualTo(2);
    }

    @Test
    void differentJobsBothMutateSuccessfully() throws Exception {
        long left = active("independent-a", 90);
        long right = active("independent-b", 80);
        CountDownLatch bothStarted = new CountDownLatch(2);

        List<Thread> threads = List.of(
                new Thread(() -> { bothStarted.countDown(); change(left, WorkflowStatus.SAVED); }),
                new Thread(() -> { bothStarted.countDown(); change(right, WorkflowStatus.APPLIED); }));
        for (Thread thread : threads) thread.start();
        assertThat(bothStarted.await(30, TimeUnit.SECONDS)).isTrue();
        for (Thread thread : threads) thread.join(30_000);

        assertThat(workflowStatus(left)).isEqualTo("SAVED");
        assertThat(workflowStatus(right)).isEqualTo("APPLIED");
        // Serialized on the server, but both committed and each got its own revision.
        assertThat(committedRevision()).isEqualTo(2);
        assertThat(ledgerRows()).isEqualTo(2);
    }

    /** I4/I5. A rolled-back attempt publishes no revision and leaves no ledger row. */
    @Test
    void aRolledBackMutationPublishesNoRevision() {
        long jobId = active("rollback-revision", 88);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(call -> {
            if (attempts.incrementAndGet() == 1) {
                throw new OptimisticLockingFailureException("injected conflict");
            }
            return call.callRealMethod();
        }).when(transitionPolicy).canCreate(any());

        var result = change(jobId, WorkflowStatus.SAVED);

        assertThat(attempts.get()).isEqualTo(2);
        // The abandoned attempt's increment rolled back with it: no gap, no phantom revision.
        assertThat(result.mutationRevision()).isEqualTo(1);
        assertThat(committedRevision()).isEqualTo(1);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    // --------------------------------------------------------------- I6 idempotency

    @Test
    void aRetriedMutationAfterACommittedResponseIsNotExecutedTwice() {
        long jobId = active("lost-response", 85);
        String mutationId = newMutationId();

        var original = workflows.change(mutationId, jobId, WorkflowStatus.SAVED);
        var retry = workflows.change(mutationId, jobId, WorkflowStatus.SAVED);

        assertThat(original.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.mutationRevision()).isEqualTo(original.mutationRevision());
        assertThat(retry.undoToken()).isEqualTo(original.undoToken());
        // The retry published no new revision and wrote no second history row.
        assertThat(committedRevision()).isEqualTo(1);
        assertThat(historyRows(jobId)).isEqualTo(1);
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    /**
     * Correction B, the case a recovery read cannot answer. The duplicate arrives while the
     * original is still open, so it must queue on the revision row rather than execute beside it
     * — and when the original commits, the duplicate must recognise it rather than fail.
     */
    @Test
    void aDuplicateArrivingWhileTheOriginalIsStillRunningExecutesOnce() throws Exception {
        long jobId = active("in-flight-duplicate", 84);
        String mutationId = newMutationId();
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        parkThread("original", parked, release);

        AtomicReference<MiniAppOperation> originalResult = new AtomicReference<>();
        Thread original = new Thread(() ->
                originalResult.set(workflows.change(mutationId, jobId, WorkflowStatus.SAVED)),
                "original");
        original.start();
        assertThat(parked.await(30, TimeUnit.SECONDS)).isTrue();

        AtomicReference<MiniAppOperation> duplicateResult = new AtomicReference<>();
        AtomicReference<Throwable> duplicateFailure = new AtomicReference<>();
        Thread duplicate = new Thread(() -> {
            try {
                duplicateResult.set(workflows.change(mutationId, jobId, WorkflowStatus.SAVED));
            } catch (Throwable failure) {
                duplicateFailure.set(failure);
            }
        }, "duplicate");
        duplicate.start();
        duplicate.join(2_000);
        assertThat(duplicate.isAlive())
                .as("the duplicate queues behind the original instead of racing it").isTrue();

        release.countDown();
        original.join(30_000);
        duplicate.join(30_000);

        assertThat(duplicateFailure.get()).isNull();
        assertThat(originalResult.get().replayed()).isFalse();
        assertThat(duplicateResult.get().replayed()).isTrue();
        assertThat(duplicateResult.get().mutationRevision())
                .isEqualTo(originalResult.get().mutationRevision());
        // Exactly one durable effect, from two deliveries of one logical mutation.
        assertThat(committedRevision()).isEqualTo(1);
        assertThat(ledgerRows()).isEqualTo(1);
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(historyRows(jobId)).isEqualTo(1);
    }

    /** The other half of Correction B: a first execution that rolled back must not be replayed. */
    @Test
    void aRetryAfterTheFirstExecutionRolledBackExecutesExactlyOnce() {
        long jobId = active("rolled-back-retry", 83);
        String mutationId = newMutationId();
        doAnswer(call -> {
            throw new IllegalStateException("synthetic failure before commit");
        }).when(transitionPolicy).canCreate(any());

        assertThatThrownBy(() -> workflows.change(mutationId, jobId, WorkflowStatus.SAVED))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledgerRows()).as("a rolled-back attempt records nothing").isZero();

        reset(transitionPolicy);
        var retry = workflows.change(mutationId, jobId, WorkflowStatus.SAVED);

        assertThat(retry.replayed()).as("nothing committed, so this is a first execution").isFalse();
        assertThat(retry.mutationRevision()).isEqualTo(1);
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(historyRows(jobId)).isEqualTo(1);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    @Test
    void theSameMutationIdWithADifferentPayloadIsATypedConflict() {
        long jobId = active("payload-mismatch", 82);
        long otherJob = active("payload-mismatch-other", 81);
        String mutationId = newMutationId();
        workflows.change(mutationId, jobId, WorkflowStatus.SAVED);

        assertThatThrownBy(() -> workflows.change(mutationId, jobId, WorkflowStatus.DISMISSED))
                .isInstanceOf(MiniAppMutationException.class)
                .satisfies(failure -> assertThat(((MiniAppMutationException) failure).getCategory())
                        .isEqualTo(MiniAppMutationException.Category.IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(() -> workflows.change(mutationId, otherJob, WorkflowStatus.SAVED))
                .isInstanceOf(MiniAppMutationException.class);

        // Neither rejected request changed anything.
        assertThat(workflowStatus(jobId)).isEqualTo("SAVED");
        assertThat(count("job_workflow_state", "job_id", otherJob)).isZero();
        assertThat(ledgerRows()).isEqualTo(1);
    }

    /**
     * The ledger stores operation identity, never a snapshot. Replaying an older mutation after
     * a newer one committed must not resurrect the older view, and must not re-arm its Undo.
     */
    @Test
    void replayingAnEarlierMutationDoesNotRegressStateOrReArmItsUndo() {
        long jobId = active("replay-after-newer", 86);
        String first = newMutationId();
        var saved = workflows.change(first, jobId, WorkflowStatus.SAVED);
        workflows.change(newMutationId(), jobId, WorkflowStatus.APPLIED);

        var replay = workflows.change(first, jobId, WorkflowStatus.SAVED);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.mutationRevision()).isEqualTo(saved.mutationRevision());
        assertThat(replay.undoToken()).as("superseded, so no capability comes back").isNull();
        // The newer mutation still stands.
        assertThat(workflowStatus(jobId)).isEqualTo("APPLIED");
        assertThat(committedRevision()).isEqualTo(2);
        assertThatThrownBy(() -> workflows.undo(newMutationId(), saved.undoToken()))
                .isInstanceOf(MiniAppMutationException.class);
    }

    // ------------------------------------------------------------------- I7/I8 undo

    @Test
    void undoOfAnApplyOverAPreExistingSavedApplicationKeepsTheRowAndItsHistory() {
        long jobId = active("undo-pre-existing", 87);
        tracker.transition(jobId, ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.INTERNAL);
        Long applicationId = applicationId(jobId);
        var applied = change(jobId, WorkflowStatus.APPLIED);

        var undone = workflows.undo(newMutationId(), applied.undoToken());

        assertThat(undone.status()).isEqualTo(WorkflowStatus.UNREVIEWED);
        assertThat(undone.applicationStatus()).isEqualTo(ApplicationStatus.SAVED);
        // Same row, restored status, and the pre-existing history row survives untouched.
        assertThat(applicationId(jobId)).isEqualTo(applicationId);
        assertThat(applicationStatus(jobId)).isEqualTo("SAVED");
        assertThat(historyStatuses(jobId)).containsExactly("SAVED");
        // The applied-at the Apply stamped is put back to what was recorded, not recomputed.
        assertThat(jdbc.queryForObject("select application_date from applications where job_id = ?",
                java.sql.Timestamp.class, jobId)).isNull();
    }

    @Test
    void undoOfMiniAppCreatedTrackingRemovesExactlyWhatItCreated() {
        long jobId = active("undo-created", 89);
        var saved = change(jobId, WorkflowStatus.SAVED);
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);

        var undone = workflows.undo(newMutationId(), saved.undoToken());

        assertThat(undone.applicationStatus()).isNull();
        // History goes before its parent: the FK is ON DELETE RESTRICT.
        assertThat(historyRows(jobId)).isZero();
        assertThat(count("applications", "job_id", jobId)).isZero();
        assertThat(count("job_workflow_state", "job_id", jobId)).isZero();
    }

    @Test
    void undoOfADismissNeverTouchesApplicationTracking() {
        long jobId = active("undo-dismiss", 78);
        tracker.transition(jobId, ApplicationStatus.APPLIED, null, null,
                ApplicationStatusChangeSource.INTERNAL);
        Long applicationId = applicationId(jobId);
        var dismissed = change(jobId, WorkflowStatus.DISMISSED);

        workflows.undo(newMutationId(), dismissed.undoToken());

        assertThat(count("job_workflow_state", "job_id", jobId)).isZero();
        assertThat(applicationId(jobId)).isEqualTo(applicationId);
        assertThat(applicationStatus(jobId)).isEqualTo("APPLIED");
        assertThat(historyStatuses(jobId)).containsExactly("APPLIED");
    }

    @Test
    void aNewerMiniAppMutationMakesTheEarlierUndoStale() {
        long jobId = active("undo-superseded", 76);
        var saved = change(jobId, WorkflowStatus.SAVED);
        change(jobId, WorkflowStatus.APPLIED);

        assertThatThrownBy(() -> workflows.undo(newMutationId(), saved.undoToken()))
                .isInstanceOf(MiniAppMutationException.class)
                .satisfies(failure -> assertThat(((MiniAppMutationException) failure).getCategory())
                        .isEqualTo(MiniAppMutationException.Category.UNDO_STALE));

        assertThat(workflowStatus(jobId)).isEqualTo("APPLIED");
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
    }

    /**
     * I7's real point. An external writer advances no Mini App revision at all, so only the
     * recorded fingerprint can catch it. Without that check, provenance alone would happily
     * delete an application row a Telegram command had since moved on.
     */
    @Test
    void anExternalTelegramTransitionMakesTheUndoStaleWithoutDeletingAnything() {
        long jobId = active("undo-external", 75);
        var saved = change(jobId, WorkflowStatus.SAVED);
        long revisionBefore = committedRevision();

        // The Telegram command / ApplicationController path: no Mini App revision is advanced.
        tracker.transition(jobId, ApplicationStatus.APPLIED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        assertThat(committedRevision()).isEqualTo(revisionBefore);

        assertThatThrownBy(() -> workflows.undo(newMutationId(), saved.undoToken()))
                .isInstanceOf(MiniAppMutationException.class)
                .satisfies(failure -> assertThat(((MiniAppMutationException) failure).getCategory())
                        .isEqualTo(MiniAppMutationException.Category.UNDO_STALE));

        // Nothing deleted, nothing rolled back over the newer external action.
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(applicationStatus(jobId)).isEqualTo("APPLIED");
        assertThat(historyStatuses(jobId)).containsExactly("SAVED", "APPLIED");
        assertThat(workflowStatus(jobId)).isEqualTo("SAVED");
    }

    /**
     * The mirror of the deletion case: a Reset removes the workflow row, so reversing it has to
     * put one back. Restoring only ever *updated* an existing row, which made every Reset
     * permanently unundoable behind a conflict that described nothing real.
     */
    @Test
    void undoOfAResetRecreatesTheWorkflowRowItDeleted() {
        long jobId = active("undo-reset", 73);
        change(jobId, WorkflowStatus.APPLIED);
        assertThat(workflowStatus(jobId)).isEqualTo("APPLIED");
        Instant appliedAt = jdbc.queryForObject(
                "select applied_at from job_workflow_state where job_id = ?", Instant.class, jobId);

        var reset = change(jobId, WorkflowStatus.UNREVIEWED);
        assertThat(count("job_workflow_state", "job_id", jobId)).isZero();

        var undone = workflows.undo(newMutationId(), reset.undoToken());

        assertThat(undone.status()).isEqualTo(WorkflowStatus.APPLIED);
        assertThat(count("job_workflow_state", "job_id", jobId)).isEqualTo(1);
        assertThat(workflowStatus(jobId)).isEqualTo("APPLIED");
        // The recorded applied-at is put back, not re-derived from the reversal's own clock.
        assertThat(jdbc.queryForObject(
                "select applied_at from job_workflow_state where job_id = ?", Instant.class, jobId))
                .isEqualTo(appliedAt);
        // Reset never touched tracking, so reversing it does not either.
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(historyStatuses(jobId)).containsExactly("APPLIED");
    }

    /**
     * The workflow-only external write. {@code /note} changes {@code job_workflow_state.note}
     * and nothing else: the status is untouched and the application is never opened, so neither
     * the workflow status nor the application {@code @Version} moves. Only a workflow-side
     * version can catch it — and without one, reversing here would silently overwrite a note
     * the user had just written from Telegram.
     */
    @Test
    void anExternalNoteMakesTheUndoStaleWithoutDestroyingIt() {
        long jobId = active("undo-external-note", 72);
        var saved = change(jobId, WorkflowStatus.SAVED);
        long revisionBefore = committedRevision();

        review.note(jobId, "Recruiter replied, call on Thursday");
        assertThat(committedRevision()).as("an external note advances no Mini App revision")
                .isEqualTo(revisionBefore);

        assertThatThrownBy(() -> workflows.undo(newMutationId(), saved.undoToken()))
                .isInstanceOf(MiniAppMutationException.class)
                .satisfies(failure -> assertThat(((MiniAppMutationException) failure).getCategory())
                        .isEqualTo(MiniAppMutationException.Category.UNDO_STALE));

        // The newer note survives, and nothing else was rolled back either.
        assertThat(workflowNote(jobId)).isEqualTo("Recruiter replied, call on Thursday");
        assertThat(workflowStatus(jobId)).isEqualTo("SAVED");
        assertThat(count("applications", "job_id", jobId)).isEqualTo(1);
        assertThat(historyStatuses(jobId)).containsExactly("SAVED");
        assertThat(committedRevision()).isEqualTo(revisionBefore);
    }

    /** The inverse: with no external write, the same Undo still applies normally. */
    @Test
    void anUndoStillAppliesWhenNoExternalWriteHappened() {
        long jobId = active("undo-no-external-note", 71);
        var saved = change(jobId, WorkflowStatus.SAVED);

        var undone = workflows.undo(newMutationId(), saved.undoToken());

        assertThat(undone.status()).isEqualTo(WorkflowStatus.UNREVIEWED);
        assertThat(count("job_workflow_state", "job_id", jobId)).isZero();
        assertThat(count("applications", "job_id", jobId)).isZero();
        assertThat(historyRows(jobId)).isZero();
    }

    @Test
    void replayingASuccessfulUndoDoesNotReverseTwice() {
        long jobId = active("undo-replay", 74);
        tracker.transition(jobId, ApplicationStatus.SAVED, null, null,
                ApplicationStatusChangeSource.INTERNAL);
        var applied = change(jobId, WorkflowStatus.APPLIED);
        String undoMutationId = newMutationId();

        var first = workflows.undo(undoMutationId, applied.undoToken());
        var replay = workflows.undo(undoMutationId, applied.undoToken());

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.mutationRevision()).isEqualTo(first.mutationRevision());
        assertThat(applicationStatus(jobId)).isEqualTo("SAVED");
        assertThat(historyStatuses(jobId)).containsExactly("SAVED");
        assertThat(committedRevision()).isEqualTo(first.mutationRevision());
    }

    // ----------------------------------------------------------------- I3 read model

    /**
     * The isolation is verified by asking PostgreSQL, not by trusting the annotation. Spring's
     * {@code @Transactional(readOnly = true)} does <em>not</em> imply REPEATABLE READ, so this
     * asserts the level actually in force on the connection running the snapshot.
     */
    @Test
    void theSnapshotReadReallyRunsAtRepeatableRead() {
        active("isolation-probe", 70);
        AtomicReference<String> isolation = new AtomicReference<>();
        doAnswer(call -> {
            isolation.set(jdbc.queryForObject(
                    "select current_setting('transaction_isolation')", String.class));
            return call.callRealMethod();
        }).when(queries).stats();

        snapshots.snapshot();

        assertThat(isolation.get()).isEqualTo("repeatable read");
    }

    /**
     * I3. A snapshot must be one database moment. The out-of-band commit lands after the read's
     * first statement, so at READ COMMITTED the later statements would see it and the response
     * would mix two moments — counts from after, rows from before.
     */
    @Test
    void aSnapshotIsOneDatabaseMomentEvenWhenACommitLandsMidRead() throws Exception {
        active("coherent-existing", 90);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            Object stats = call.callRealMethod();
            // Exactly once, and only after the snapshot's first statement has fixed its view.
            if (reads.incrementAndGet() == 1) {
                Thread outOfBand = new Thread(() -> active("coherent-injected", 95));
                outOfBand.start();
                outOfBand.join(30_000);
            }
            return stats;
        }).when(queries).stats();

        MiniAppSnapshot snapshot = snapshots.snapshot();

        // One moment: the row list and the totals agree, and neither includes the late commit.
        assertThat(snapshot.reviewQueue().items()).hasSize(1);
        assertThat(snapshot.reviewQueue().total()).isEqualTo(1);
        assertThat(snapshot.workflowCounts().unreviewedMatch()).isEqualTo(1);
        // A later read sees it, which proves the commit really did land during the first one.
        assertThat(snapshots.snapshot().reviewQueue().total()).isEqualTo(2);
    }

    /**
     * Correction A, end to end on the server. An out-of-band writer changes what the snapshot
     * shows without advancing the Mini App revision; a Mini App mutation then commits with a
     * <em>higher</em> revision. The external change must still be in the authoritative read —
     * which it can only be because the mutation carries no global state to overwrite it with.
     */
    @Test
    void anOutOfBandChangeSurvivesALaterHigherRevisionMutation() {
        long tracked = active("out-of-band-existing", 90);
        long other = active("out-of-band-other", 80);
        change(tracked, WorkflowStatus.SAVED);
        long revisionBefore = committedRevision();

        // Ingestion-equivalent: new Review vacancy, no Mini App revision advanced.
        long injected = active("out-of-band-injected", 92);
        assertThat(committedRevision()).isEqualTo(revisionBefore);
        assertThat(snapshots.snapshot().mutationRevision()).isEqualTo(revisionBefore);

        var later = change(other, WorkflowStatus.DISMISSED);
        assertThat(later.mutationRevision()).isGreaterThan(revisionBefore);

        MiniAppSnapshot after = snapshots.snapshot();
        assertThat(after.reviewQueue().items())
                .extracting(MiniAppSnapshot.MiniAppJob::id).contains(injected);
        assertThat(after.saved().items())
                .extracting(MiniAppSnapshot.MiniAppJob::id).containsExactly(tracked);
    }

    /**
     * The equal-revision case. Two genuinely different states carry the same revision, which is
     * exactly why the client must not order reads by it.
     */
    @Test
    void anOutOfBandChangeIsVisibleAtAnUnchangedRevision() {
        long jobId = active("equal-revision", 88);
        change(jobId, WorkflowStatus.SAVED);
        MiniAppSnapshot before = snapshots.snapshot();

        tracker.transition(jobId, ApplicationStatus.APPLIED, null, null,
                ApplicationStatusChangeSource.TELEGRAM_COMMAND);
        MiniAppSnapshot after = snapshots.snapshot();

        assertThat(after.mutationRevision()).isEqualTo(before.mutationRevision());
        assertThat(before.applicationCounts().saved()).isEqualTo(1);
        assertThat(after.applicationCounts().applied()).isEqualTo(1);
        assertThat(after.applicationCounts().saved()).isZero();
    }

    // ------------------------------------------------------------------------ helpers

    /** Parks the named thread inside the mutation transaction, after it holds the revision lock. */
    private void parkThread(String threadName, CountDownLatch parked, CountDownLatch release) {
        AtomicInteger parks = new AtomicInteger();
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals(threadName)
                    && parks.incrementAndGet() == 1) {
                parked.countDown();
                release.await(30, TimeUnit.SECONDS);
            }
            return call.callRealMethod();
        }).when(transitionPolicy).canCreate(any());
    }

    /** Each call is a distinct user action, so each gets its own idempotency key. */
    private MiniAppOperation change(long jobId, WorkflowStatus status) {
        return workflows.change(newMutationId(), jobId, status);
    }

    private static String newMutationId() {
        return UUID.randomUUID().toString();
    }

    private long committedRevision() {
        return jdbc.queryForObject(
                "select mutation_revision from mini_app_state where id = 1", Long.class);
    }

    private int ledgerRows() {
        return jdbc.queryForObject("select count(*) from mini_app_mutation", Integer.class);
    }

    private String workflowNote(long jobId) {
        return jdbc.queryForObject(
                "select note from job_workflow_state where job_id = ?", String.class, jobId);
    }

    private String workflowStatus(long jobId) {
        return jdbc.queryForObject(
                "select status from job_workflow_state where job_id = ?", String.class, jobId);
    }

    private String applicationStatus(long jobId) {
        return jdbc.queryForObject(
                "select status from applications where job_id = ?", String.class, jobId);
    }

    private Long applicationId(long jobId) {
        return jdbc.queryForObject("select id from applications where job_id = ?", Long.class, jobId);
    }

    private List<String> historyStatuses(long jobId) {
        return jdbc.queryForList("""
                select h.new_status from application_status_history h
                join applications a on a.id = h.application_id
                where a.job_id = ? order by h.changed_at, h.id
                """, String.class, jobId);
    }

    private int historyRows(long jobId) {
        return jdbc.queryForObject("""
                select count(*) from application_status_history h
                join applications a on a.id = h.application_id
                where a.job_id = ?
                """, Integer.class, jobId);
    }

    private int count(String table, String column, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Integer.class, id);
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
}
