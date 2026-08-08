package com.jobpilot.miniapp.application;

import com.jobpilot.applications.application.ApplicationMutationResult;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.applications.repository.ApplicationStatusHistoryRepository;
import com.jobpilot.jobreview.application.JobReviewException;
import com.jobpilot.jobreview.application.JobReviewService;
import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.jobreview.domain.JobWorkflowState;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobreview.repository.JobWorkflowStateRepository;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.miniapp.domain.MiniAppMutation;
import com.jobpilot.miniapp.domain.MiniAppMutationKind;
import com.jobpilot.miniapp.domain.MiniAppReversalState;
import com.jobpilot.miniapp.domain.MiniAppState;
import com.jobpilot.miniapp.repository.MiniAppMutationRepository;
import com.jobpilot.miniapp.repository.MiniAppStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every Mini App write, in one serialized and idempotent place.
 *
 * <h2>Transaction shape</h2>
 *
 * <p>One {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} transaction per attempt, at the
 * <strong>default READ COMMITTED</strong> isolation, whose first statement locks the
 * {@code mini_app_state} row and holds it to commit. That single choice buys three things:
 *
 * <ul>
 *   <li><b>revision order is commit order</b> (I4) — a sequence could not promise this, because
 *       a number can be handed out long before the transaction holding it commits;</li>
 *   <li><b>duplicates queue instead of racing</b> — a retry of an in-flight mutation blocks on
 *       the lock rather than executing beside it;</li>
 *   <li><b>a blocked mutation wakes to committed state</b> — verified against PostgreSQL 16:
 *       at REPEATABLE READ this same interleaving raises {@code could not serialize access due
 *       to concurrent update} instead, and an aborted transaction would never reach the ledger
 *       lookup that recognises the duplicate.</li>
 * </ul>
 *
 * <p>The read path takes the opposite decision for the opposite reason — see
 * {@link MiniAppSnapshotService}. Nothing here builds a snapshot: a mutation response carries no
 * global state at all, so the lock is held only for the rows this job owns.
 *
 * <h2>Why the retry sits here</h2>
 *
 * <p>A conflict can only be retried by whoever can abandon the transaction it poisoned. Each
 * attempt opens its own REQUIRES_NEW transaction, so a failed attempt's rolled-back transaction
 * and its poisoned persistence context are both discarded before the next begins. REQUIRED would
 * inherit whatever context is bound to the thread, and a narrower boundary would split the
 * workflow/application invariant this class exists to keep.
 */
@Service
public class MiniAppWorkflowService {
    private final JobReviewService review;
    private final ApplicationTrackerService applications;
    private final ApplicationRepository applicationRows;
    private final ApplicationStatusHistoryRepository history;
    private final JobWorkflowStateRepository workflowStates;
    private final JobRepository jobs;
    private final MiniAppStateRepository states;
    private final MiniAppMutationRepository ledger;
    private final MiniAppReversal reversal;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public MiniAppWorkflowService(JobReviewService review,
                                  ApplicationTrackerService applications,
                                  ApplicationRepository applicationRows,
                                  ApplicationStatusHistoryRepository history,
                                  JobWorkflowStateRepository workflowStates,
                                  JobRepository jobs,
                                  MiniAppStateRepository states,
                                  MiniAppMutationRepository ledger,
                                  MiniAppReversal reversal,
                                  Clock clock,
                                  PlatformTransactionManager transactionManager) {
        this.review = review;
        this.applications = applications;
        this.applicationRows = applicationRows;
        this.history = history;
        this.workflowStates = workflowStates;
        this.jobs = jobs;
        this.states = states;
        this.ledger = ledger;
        this.reversal = reversal;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Applies one workflow change, or resolves it as a duplicate of an earlier delivery.
     *
     * <p>Deliberately not {@code @Transactional}: the retry has to live outside the transaction
     * it retries.
     */
    public MiniAppOperation change(String mutationId, long jobId, WorkflowStatus requested) {
        return attempt(() -> forward(mutationId, jobId, requested));
    }

    /** Reverses the mutation the token addresses, or refuses with a typed stale conflict. */
    public MiniAppOperation undo(String mutationId, String undoToken) {
        return attempt(() -> reverse(mutationId, undoToken));
    }

    private MiniAppOperation attempt(java.util.function.Supplier<MiniAppOperation> operation) {
        for (int attempt = 1; attempt <= ApplicationTrackerService.MAX_CONFLICT_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(status -> operation.get());
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException conflict) {
                if (attempt == ApplicationTrackerService.MAX_CONFLICT_ATTEMPTS) {
                    throw ApplicationTrackerService.conflict();
                }
            }
        }
        throw new IllegalStateException("Unreachable conflict retry state");
    }

    // ------------------------------------------------------------------ forward

    private MiniAppOperation forward(String mutationId, long jobId, WorkflowStatus requested) {
        MiniAppState state = lockRevisionRow();

        // Inside the lock, never before it: checking earlier would let a duplicate slip past the
        // original instead of queueing behind it, which is the whole recovery mechanism.
        MiniAppMutation duplicate = ledger.findByMutationKey(mutationId).orElse(null);
        if (duplicate != null) {
            return replay(duplicate, MiniAppMutationKind.WORKFLOW, jobId, requested);
        }

        // Locked in the order every other writer uses — job, then workflow, then application —
        // so a concurrent Telegram command cannot deadlock against this one.
        requireJob(jobId);
        JobWorkflowState workflowBefore = workflowStates.findByJobIdForUpdate(jobId).orElse(null);
        ApplicationRecord applicationBefore = applicationRows.findByJobIdForUpdate(jobId).orElse(null);

        MiniAppMutation.PriorState prior = new MiniAppMutation.PriorState(
                workflowBefore == null ? null : workflowBefore.getStatus(),
                workflowBefore == null ? null : workflowBefore.getNote(),
                workflowBefore == null ? null : workflowBefore.getAppliedAt(),
                applicationBefore == null ? null : applicationBefore.getStatus(),
                applicationBefore == null ? null : applicationBefore.getApplicationDate());
        Long historyBefore = applicationBefore == null
                ? null : history.findFrontier(applicationBefore.getId());

        long revision = state.nextRevision();

        WorkflowView workflow = switch (requested) {
            case SAVED -> review.save(jobId);
            case APPLIED -> review.applied(jobId);
            case DISMISSED -> review.dismiss(jobId);
            case UNREVIEWED -> review.reset(jobId);
        };
        boolean changed = workflow.changed();

        if (requested == WorkflowStatus.SAVED || requested == WorkflowStatus.APPLIED) {
            ApplicationStatus tracked = requested == WorkflowStatus.SAVED
                    ? ApplicationStatus.SAVED : ApplicationStatus.APPLIED;
            // One attempt joined to this transaction; the retry above owns the second chance.
            ApplicationMutationResult result = applications.transitionInCurrentTransaction(
                    jobId, tracked, null, null, ApplicationStatusChangeSource.INTERNAL);
            changed = changed || result.changed();
        }

        ApplicationRecord applicationAfter = applicationRows.findByJobId(jobId).orElse(null);
        Long historyAfter = applicationAfter == null
                ? null : history.findFrontier(applicationAfter.getId());
        // A moved frontier is this mutation's own row: the application is locked for the whole
        // transaction, so nothing else could have appended to it.
        Long createdHistoryId = Objects.equals(historyBefore, historyAfter) ? null : historyAfter;

        // Re-read under the lock we already hold: the flush that persisted the change also
        // advanced the workflow version, and it is that post-mutation value a reversal must find.
        JobWorkflowState workflowAfter = workflowStates.findByJobIdForUpdate(jobId).orElse(null);

        MiniAppMutation.ResultingState resulting = new MiniAppMutation.ResultingState(
                workflowAfter == null ? null : workflowAfter.getStatus(),
                workflowAfter == null ? null : workflowAfter.getVersion(),
                applicationAfter == null ? null : applicationAfter.getStatus(),
                applicationAfter == null ? null : applicationAfter.getVersion(),
                historyAfter,
                applicationBefore == null && applicationAfter != null,
                createdHistoryId);

        // Undo is offered only for a mutation that actually moved something. A no-op has nothing
        // to reverse, and arming it would let a stale token reverse a later real action.
        String undoToken = changed ? UUID.randomUUID().toString() : null;
        supersedeEarlierUndo(jobId);
        MiniAppMutation recorded = ledger.save(MiniAppMutation.workflow(mutationId, jobId,
                requested, revision, changed, prior, resulting, undoToken, clock.instant()));

        return new MiniAppOperation(mutationId, revision, false, jobId,
                workflow.status(), changed, resulting.applicationStatus(),
                recorded.getUndoToken());
    }

    // ------------------------------------------------------------------ reversal

    private MiniAppOperation reverse(String mutationId, String undoToken) {
        MiniAppState state = lockRevisionRow();

        MiniAppMutation duplicate = ledger.findByMutationKey(mutationId).orElse(null);
        if (duplicate != null) {
            // A replayed undo must not reverse twice; the recorded outcome is the answer.
            return replayUndo(duplicate, undoToken);
        }

        MiniAppMutation original = ledger.findByUndoToken(undoToken)
                .orElseThrow(MiniAppMutationException::undoStale);
        long jobId = original.getJobId();

        requireJob(jobId);
        JobWorkflowState workflow = workflowStates.findByJobIdForUpdate(jobId).orElse(null);
        ApplicationRecord application = applicationRows.findByJobIdForUpdate(jobId).orElse(null);

        // Both halves of I7: still the newest Mini App mutation for this job, and the durable
        // state still matches the fingerprint it produced.
        MiniAppMutation newest = ledger.findTopByJobIdOrderByMutationRevisionDesc(jobId)
                .orElseThrow(MiniAppMutationException::undoStale);
        if (!Objects.equals(newest.getId(), original.getId())) {
            throw MiniAppMutationException.undoStale();
        }
        reversal.assertStillReversible(original, workflow, application);

        MiniAppMutation.PriorState prior = new MiniAppMutation.PriorState(
                workflow == null ? null : workflow.getStatus(),
                workflow == null ? null : workflow.getNote(),
                workflow == null ? null : workflow.getAppliedAt(),
                application == null ? null : application.getStatus(),
                application == null ? null : application.getApplicationDate());

        long revision = state.nextRevision();
        Instant now = clock.instant();
        ApplicationStatus resultingApplication = reversal.restore(original, workflow, application, now);

        WorkflowStatus restored = original.getPreviousWorkflowStatus() == null
                ? WorkflowStatus.UNREVIEWED : original.getPreviousWorkflowStatus();
        ApplicationRecord after = applicationRows.findByJobId(jobId).orElse(null);
        JobWorkflowState workflowAfter = workflowStates.findByJobIdForUpdate(jobId).orElse(null);

        MiniAppMutation.ResultingState resulting = new MiniAppMutation.ResultingState(
                workflowAfter == null ? null : workflowAfter.getStatus(),
                workflowAfter == null ? null : workflowAfter.getVersion(),
                resultingApplication,
                after == null ? null : after.getVersion(),
                after == null ? null : history.findFrontier(after.getId()),
                false,
                null);

        MiniAppMutation record = ledger.save(MiniAppMutation.reversal(mutationId, jobId,
                restored, revision, prior, resulting, now));
        // Clearing the original's token is what makes a replayed undo a no-op rather than a
        // second reversal.
        original.markReversed(record.getId());
        ledger.save(original);

        return new MiniAppOperation(mutationId, revision, false, jobId, restored, true,
                resultingApplication, null);
    }

    // ------------------------------------------------------------------ shared

    /**
     * The first statement of every mutation. Held until commit, which is what makes revision
     * order commit order and what makes a duplicate wait rather than race.
     */
    private MiniAppState lockRevisionRow() {
        return states.findByIdForUpdate(MiniAppState.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException(
                        "mini_app_state singleton row is missing; V13 did not run"));
    }

    /**
     * Answers a duplicate delivery from the ledger without re-executing anything.
     *
     * <p>Carries no global state, so however late this arrives it cannot regress a client past a
     * newer mutation. The undo capability is read live: a superseding mutation will have cleared
     * this one's token, so a replay never re-arms a stale Undo.
     */
    private MiniAppOperation replay(MiniAppMutation recorded, MiniAppMutationKind kind,
                                    long jobId, WorkflowStatus requested) {
        if (recorded.getKind() != kind || recorded.getJobId() != jobId
                || recorded.getRequestedStatus() != requested) {
            throw MiniAppMutationException.idempotencyConflict();
        }
        return recordedResult(recorded, liveUndoToken(recorded));
    }

    /**
     * A replayed undo, verified against the token it originally consumed.
     *
     * <p>The token survives the reversal precisely so this comparison is possible: the original
     * mutation points at the reversal that spent it, so re-using one mutation id to undo a
     * different mutation is a payload mismatch rather than a silent no-op.
     */
    private MiniAppOperation replayUndo(MiniAppMutation recorded, String undoToken) {
        if (recorded.getKind() != MiniAppMutationKind.REVERSAL) {
            throw MiniAppMutationException.idempotencyConflict();
        }
        MiniAppMutation original = ledger.findByUndoToken(undoToken).orElse(null);
        if (original == null || !Objects.equals(original.getReversedByMutationId(), recorded.getId())) {
            throw MiniAppMutationException.idempotencyConflict();
        }
        return recordedResult(recorded, liveUndoToken(recorded));
    }

    private MiniAppOperation recordedResult(MiniAppMutation recorded, String liveUndoToken) {
        WorkflowStatus status = recorded.getResultingWorkflowStatus() == null
                ? WorkflowStatus.UNREVIEWED : recorded.getResultingWorkflowStatus();
        return new MiniAppOperation(recorded.getMutationKey(), recorded.getMutationRevision(),
                true, recorded.getJobId(), status, recorded.isChanged(),
                recorded.getResultingApplicationStatus(), liveUndoToken);
    }

    /**
     * Whether this recorded mutation can still be undone <em>now</em>, answered against durable
     * state rather than against its own ledger column.
     *
     * <p>{@code reversalState} only moves when another Mini App mutation supersedes or reverses
     * this one. A Telegram note or an application transition leaves it untouched while making the
     * reversal impossible, so reading it alone would re-arm a capability the undo endpoint would
     * immediately refuse — and the client would show an Undo button that cannot work.
     *
     * <p>The rows are locked in the same order as every other path here (job, workflow,
     * application, all under the revision row already held), so this introduces no new lock
     * ordering and cannot deadlock against a concurrent Telegram command.
     */
    private String liveUndoToken(MiniAppMutation recorded) {
        if (!recorded.isReversible()) return null;
        long jobId = recorded.getJobId();
        requireJob(jobId);
        JobWorkflowState workflow = workflowStates.findByJobIdForUpdate(jobId).orElse(null);
        ApplicationRecord application = applicationRows.findByJobIdForUpdate(jobId).orElse(null);
        return reversal.isStillReversible(recorded, workflow, application)
                ? recorded.getUndoToken() : null;
    }

    /** A newer mutation for the same job retires every older undo capability on it. */
    private void supersedeEarlierUndo(long jobId) {
        ledger.findByJobIdAndReversalState(jobId, MiniAppReversalState.REVERSIBLE)
                .forEach(MiniAppMutation::supersede);
    }

    /**
     * Takes the job row lock first, matching the order {@code JobReviewService} and the Telegram
     * command path use. Locking the workflow row before the job row would invert that order and
     * make a Mini App mutation deadlock against a concurrent Telegram command.
     */
    private void requireJob(long jobId) {
        if (jobId <= 0 || jobs.findByIdForUpdate(jobId).isEmpty()) {
            throw new JobReviewException(JobReviewException.Category.JOB_NOT_FOUND,
                    "Vacancy was not found.");
        }
    }
}
