package com.jobpilot.miniapp.application;

import com.jobpilot.applications.application.ApplicationMutationResult;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.jobreview.application.JobReviewService;
import com.jobpilot.jobreview.application.WorkflowView;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the Mini App invariant: SAVED/APPLIED workflow and application tracking commit as one
 * unit. Reset deliberately preserves an existing application; deterministic Undo ownership is
 * deferred to P0-B and must never infer that a historical application is safe to delete.
 *
 * <h2>Why the retry sits here and not inside the tracker</h2>
 *
 * <p>A conflict can only be retried by whoever can abandon the transaction it poisoned. When
 * ApplicationTrackerService retried inside its own REQUIRED TransactionTemplate while this
 * class held an outer {@code @Transactional}, the failed attempt marked that outer transaction
 * rollback-only and every later attempt ran inside a transaction that could never commit —
 * surfacing as UnexpectedRollbackException or, once the flush had poisoned the persistence
 * context, a Hibernate AssertionFailure.
 *
 * <p>So the retry moved outward to the only boundary that owns the whole unit. Each attempt runs
 * in a {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW} transaction, which is what
 * guarantees a fresh attempt rather than merely a repeated one: REQUIRES_NEW suspends any bound
 * EntityManager and opens its own, so a failed attempt's rolled-back transaction and its
 * poisoned persistence context are both closed and discarded before the next attempt begins.
 * REQUIRED would inherit whatever context is already bound to the thread, and REQUIRES_NEW
 * around only the application transition would split the very invariant this class exists for.
 */
@Service
public class MiniAppWorkflowService {
    private final JobReviewService review;
    private final ApplicationTrackerService applications;
    private final MiniAppSnapshotService snapshots;
    private final TransactionTemplate transactions;

    public MiniAppWorkflowService(JobReviewService review,
                                  ApplicationTrackerService applications,
                                  MiniAppSnapshotService snapshots,
                                  PlatformTransactionManager transactionManager) {
        this.review = review;
        this.applications = applications;
        this.snapshots = snapshots;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Deliberately not {@code @Transactional}: the retry has to live outside the transaction it
     * retries. Exhausting the attempts raises the same CONFLICT the standalone tracker raises,
     * so both reach the controller's existing typed 409.
     */
    public MiniAppWorkflowResult change(long jobId, WorkflowStatus requested) {
        for (int attempt = 1; attempt <= ApplicationTrackerService.MAX_CONFLICT_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(status -> attempt(jobId, requested));
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException conflict) {
                if (attempt == ApplicationTrackerService.MAX_CONFLICT_ATTEMPTS) {
                    throw ApplicationTrackerService.conflict();
                }
            }
        }
        throw new IllegalStateException("Unreachable conflict retry state");
    }

    /** One whole attempt — workflow, application and snapshot — inside a single transaction. */
    private MiniAppWorkflowResult attempt(long jobId, WorkflowStatus requested) {
        WorkflowView workflow = switch (requested) {
            case SAVED -> review.save(jobId);
            case APPLIED -> review.applied(jobId);
            case DISMISSED -> review.dismiss(jobId);
            case UNREVIEWED -> review.reset(jobId);
        };
        boolean changed = workflow.changed();
        if (requested == WorkflowStatus.SAVED || requested == WorkflowStatus.APPLIED) {
            ApplicationStatus status = requested == WorkflowStatus.SAVED
                    ? ApplicationStatus.SAVED : ApplicationStatus.APPLIED;
            // One attempt joined to this transaction; the retry above owns the second chance.
            ApplicationMutationResult tracked = applications.transitionInCurrentTransaction(
                    jobId, status, null, null, ApplicationStatusChangeSource.INTERNAL);
            changed = changed || tracked.changed();
        }
        return new MiniAppWorkflowResult(workflow, changed, snapshots.snapshot());
    }
}
