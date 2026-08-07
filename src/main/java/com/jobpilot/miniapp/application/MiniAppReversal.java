package com.jobpilot.miniapp.application;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.applications.repository.ApplicationStatusHistoryRepository;
import com.jobpilot.jobreview.domain.JobWorkflowState;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobreview.repository.JobWorkflowStateRepository;
import com.jobpilot.miniapp.domain.MiniAppMutation;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The mechanics of undoing one recorded Mini App mutation, always inside the caller's
 * transaction and always after {@link #assertStillReversible} has passed.
 *
 * <p>Split from {@link MiniAppWorkflowService} because it is the only code in the system allowed
 * to delete durable rows and to move an application backwards. Both powers are licensed
 * exclusively by the ledger: this mutation recorded that it created the row, and the fingerprint
 * proves nothing has touched it since. Neither is ever inferred from current status (I8).
 *
 * <p>There is one restore rule, not one per case — see the Undo section of
 * docs/mini-app-p0b-consistency-model.md.
 */
@Component
public class MiniAppReversal {
    private final JobWorkflowStateRepository workflowStates;
    private final ApplicationRepository applications;
    private final ApplicationStatusHistoryRepository history;

    public MiniAppReversal(JobWorkflowStateRepository workflowStates,
                           ApplicationRepository applications,
                           ApplicationStatusHistoryRepository history) {
        this.workflowStates = workflowStates;
        this.applications = applications;
        this.history = history;
    }

    /**
     * Refuses unless the durable state still matches the fingerprint the mutation produced.
     *
     * <p>Provenance says what the mutation made; it does not say that is still what is there.
     * A Telegram command or ApplicationController write moves the application {@code @Version}
     * and appends history without touching the Mini App revision, so this check — not the
     * revision — is what makes an externally superseded Undo stale. No timestamps involved.
     *
     * @param workflow    the current workflow row, or null when the job is implicitly UNREVIEWED
     * @param application the current application row, or null when the job is untracked
     */
    public void assertStillReversible(MiniAppMutation mutation, JobWorkflowState workflow,
                                      ApplicationRecord application) {
        if (!mutation.isReversible()) throw MiniAppMutationException.undoStale();

        WorkflowStatus currentWorkflow = workflow == null ? null : workflow.getStatus();
        if (!Objects.equals(currentWorkflow, mutation.getResultingWorkflowStatus())) {
            throw MiniAppMutationException.undoStale();
        }

        if (mutation.getResultingApplicationVersion() == null) {
            // The mutation left the job untracked. Anything tracking it now arrived afterwards.
            if (application != null) throw MiniAppMutationException.undoStale();
            return;
        }
        if (application == null
                || application.getVersion() != mutation.getResultingApplicationVersion()
                || !Objects.equals(history.findFrontier(application.getId()),
                        mutation.getResultingHistoryId())) {
            throw MiniAppMutationException.undoStale();
        }
    }

    /**
     * Puts back exactly what the mutation recorded, in the one order the schema permits.
     *
     * <p>History before application: {@code application_status_history.application_id} is
     * {@code ON DELETE RESTRICT}, so the child row this mutation appended must go first or the
     * parent delete is refused.
     *
     * @return the application status the job is left with, or null when it ends up untracked
     */
    public ApplicationStatus restore(MiniAppMutation mutation, JobWorkflowState workflow,
                                     ApplicationRecord application, Instant now) {
        restoreWorkflow(mutation, workflow, now);

        if (mutation.getCreatedHistoryId() != null) {
            history.findById(mutation.getCreatedHistoryId()).ifPresent(history::delete);
            history.flush();
        }

        if (application == null) return null;

        if (mutation.isCreatedApplication()) {
            // The fingerprint already proved nothing touched this application, so its only
            // history was the row just deleted. Verified rather than assumed: silently leaving
            // an orphaned row would be worse than refusing.
            if (history.countByApplicationId(application.getId()) > 0) {
                throw MiniAppMutationException.undoStale();
            }
            applications.delete(application);
            applications.flush();
            return null;
        }

        ApplicationStatus previous = mutation.getPreviousApplicationStatus();
        if (previous != null && previous != application.getStatus()) {
            application.restore(previous, mutation.getPreviousApplicationAppliedAt(), now);
            applications.saveAndFlush(application);
        }
        return application.getStatus();
    }

    /** Null previous status means the job had no workflow row at all, so the row is removed. */
    private void restoreWorkflow(MiniAppMutation mutation, JobWorkflowState workflow, Instant now) {
        WorkflowStatus previous = mutation.getPreviousWorkflowStatus();
        if (previous == null) {
            if (workflow != null) {
                workflowStates.delete(workflow);
                // The queue read model goes through JDBC and would not see an unflushed delete.
                workflowStates.flush();
            }
            return;
        }
        if (workflow == null) {
            throw MiniAppMutationException.undoStale();
        }
        workflow.restore(previous, mutation.getPreviousWorkflowNote(),
                mutation.getPreviousWorkflowAppliedAt(), now);
        workflowStates.flush();
    }
}
