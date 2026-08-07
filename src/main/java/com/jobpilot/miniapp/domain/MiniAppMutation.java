package com.jobpilot.miniapp.domain;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One accepted Mini App mutation.
 *
 * <p>Three jobs in one row: the idempotency key, the durable state immediately <em>before</em>
 * the mutation (so Undo restores rather than guesses), and a fingerprint of the state it
 * <em>produced</em> (so a stale Undo is provable rather than assumed).
 *
 * <p>The two halves are not interchangeable. {@code createdApplication} and
 * {@code createdHistoryId} are provenance — they say what this mutation made, and are the
 * only basis on which a reversal may delete anything. The {@code resulting*} fields are
 * freshness — they say whether that is still the state being reversed. A Telegram command
 * changing the same application moves the fingerprint without touching provenance, and must
 * make the old undo stale.
 */
@Entity
@Table(name = "mini_app_mutation")
public class MiniAppMutation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mutation_key", nullable = false, updatable = false, unique = true)
    private String mutationKey;

    @Column(name = "job_id", nullable = false, updatable = false)
    private long jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private MiniAppMutationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_status", nullable = false, updatable = false)
    private WorkflowStatus requestedStatus;

    @Column(name = "mutation_revision", nullable = false, updatable = false, unique = true)
    private long mutationRevision;

    @Column(name = "changed", nullable = false, updatable = false)
    private boolean changed;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_workflow_status", updatable = false)
    private WorkflowStatus previousWorkflowStatus;

    @Column(name = "previous_workflow_note", updatable = false)
    private String previousWorkflowNote;

    @Column(name = "previous_workflow_applied_at", updatable = false)
    private Instant previousWorkflowAppliedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_application_status", updatable = false)
    private ApplicationStatus previousApplicationStatus;

    @Column(name = "previous_application_applied_at", updatable = false)
    private Instant previousApplicationAppliedAt;

    @Column(name = "created_application", nullable = false, updatable = false)
    private boolean createdApplication;

    @Column(name = "created_history_id", updatable = false)
    private Long createdHistoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_workflow_status", updatable = false)
    private WorkflowStatus resultingWorkflowStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_application_status", updatable = false)
    private ApplicationStatus resultingApplicationStatus;

    @Column(name = "resulting_application_version", updatable = false)
    private Long resultingApplicationVersion;

    @Column(name = "resulting_history_id", updatable = false)
    private Long resultingHistoryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reversal_state", nullable = false)
    private MiniAppReversalState reversalState;

    @Column(name = "reversed_by_mutation_id")
    private Long reversedByMutationId;

    @Column(name = "undo_token")
    private String undoToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MiniAppMutation() {
    }

    private MiniAppMutation(String mutationKey, long jobId, MiniAppMutationKind kind,
                            WorkflowStatus requestedStatus, long mutationRevision, boolean changed,
                            PriorState prior, ResultingState resulting, String undoToken,
                            Instant now) {
        this.mutationKey = mutationKey;
        this.jobId = jobId;
        this.kind = kind;
        this.requestedStatus = requestedStatus;
        this.mutationRevision = mutationRevision;
        this.changed = changed;
        this.previousWorkflowStatus = prior.workflowStatus();
        this.previousWorkflowNote = prior.workflowNote();
        this.previousWorkflowAppliedAt = prior.workflowAppliedAt();
        this.previousApplicationStatus = prior.applicationStatus();
        this.previousApplicationAppliedAt = prior.applicationAppliedAt();
        // Stated by the writer that did the insert, never inferred from before/after shape:
        // deletion is allowed only on proof, and an inference is not proof (I8).
        this.createdApplication = resulting.createdApplication();
        this.createdHistoryId = resulting.createdHistoryId();
        this.resultingWorkflowStatus = resulting.workflowStatus();
        this.resultingApplicationStatus = resulting.applicationStatus();
        this.resultingApplicationVersion = resulting.applicationVersion();
        this.resultingHistoryId = resulting.historyId();
        this.undoToken = undoToken;
        this.reversalState = undoToken == null
                ? MiniAppReversalState.NOT_REVERSIBLE : MiniAppReversalState.REVERSIBLE;
        this.createdAt = now;
    }

    /** A forward workflow mutation. A null {@code undoToken} records it as not reversible. */
    public static MiniAppMutation workflow(String mutationKey, long jobId,
                                           WorkflowStatus requestedStatus, long mutationRevision,
                                           boolean changed, PriorState prior,
                                           ResultingState resulting, String undoToken, Instant now) {
        return new MiniAppMutation(mutationKey, jobId, MiniAppMutationKind.WORKFLOW,
                requestedStatus, mutationRevision, changed, prior, resulting, undoToken, now);
    }

    /** A reversal. Never itself reversible, so undoing an undo is impossible by construction. */
    public static MiniAppMutation reversal(String mutationKey, long jobId,
                                           WorkflowStatus restoredStatus, long mutationRevision,
                                           PriorState prior, ResultingState resulting, Instant now) {
        return new MiniAppMutation(mutationKey, jobId, MiniAppMutationKind.REVERSAL,
                restoredStatus, mutationRevision, true, prior, resulting, null, now);
    }

    /** A newer mutation for the same job arrived; this one can no longer be undone. */
    public void supersede() {
        if (reversalState != MiniAppReversalState.REVERSIBLE) return;
        reversalState = MiniAppReversalState.SUPERSEDED;
    }

    /** Consumed by a reversal. The state change, not a cleared token, is what blocks a replay. */
    public void markReversed(long reversedBy) {
        reversalState = MiniAppReversalState.REVERSED;
        reversedByMutationId = reversedBy;
    }

    public boolean isReversible() {
        return reversalState == MiniAppReversalState.REVERSIBLE;
    }

    /** The durable state a reversal restores, recorded verbatim so nothing is recomputed. */
    public record PriorState(WorkflowStatus workflowStatus, String workflowNote,
                             Instant workflowAppliedAt, ApplicationStatus applicationStatus,
                             Instant applicationAppliedAt) {
    }

    /**
     * What the mutation produced: the fingerprint a reversal must still find or refuse
     * ({@code workflowStatus}, {@code applicationVersion}, {@code historyId}), the provenance
     * that licenses deletion ({@code createdApplication}, {@code createdHistoryId}), and the
     * application status a replay reports.
     */
    public record ResultingState(WorkflowStatus workflowStatus, ApplicationStatus applicationStatus,
                                 Long applicationVersion, Long historyId,
                                 boolean createdApplication, Long createdHistoryId) {
    }

    public Long getId() {
        return id;
    }

    public String getMutationKey() {
        return mutationKey;
    }

    public long getJobId() {
        return jobId;
    }

    public MiniAppMutationKind getKind() {
        return kind;
    }

    public WorkflowStatus getRequestedStatus() {
        return requestedStatus;
    }

    public long getMutationRevision() {
        return mutationRevision;
    }

    public boolean isChanged() {
        return changed;
    }

    public WorkflowStatus getPreviousWorkflowStatus() {
        return previousWorkflowStatus;
    }

    public String getPreviousWorkflowNote() {
        return previousWorkflowNote;
    }

    public Instant getPreviousWorkflowAppliedAt() {
        return previousWorkflowAppliedAt;
    }

    public ApplicationStatus getPreviousApplicationStatus() {
        return previousApplicationStatus;
    }

    public Instant getPreviousApplicationAppliedAt() {
        return previousApplicationAppliedAt;
    }

    public boolean isCreatedApplication() {
        return createdApplication;
    }

    public Long getCreatedHistoryId() {
        return createdHistoryId;
    }

    public WorkflowStatus getResultingWorkflowStatus() {
        return resultingWorkflowStatus;
    }

    public ApplicationStatus getResultingApplicationStatus() {
        return resultingApplicationStatus;
    }

    public Long getResultingApplicationVersion() {
        return resultingApplicationVersion;
    }

    public Long getResultingHistoryId() {
        return resultingHistoryId;
    }

    public MiniAppReversalState getReversalState() {
        return reversalState;
    }

    public Long getReversedByMutationId() {
        return reversedByMutationId;
    }

    public String getUndoToken() {
        return undoToken;
    }
}
