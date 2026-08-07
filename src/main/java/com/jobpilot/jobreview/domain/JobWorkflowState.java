package com.jobpilot.jobreview.domain;

import com.jobpilot.jobs.domain.Job;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "job_workflow_state")
public class JobWorkflowState {
    @Id
    private Long jobId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkflowStatus status;

    @Column(length = 1000)
    private String note;

    private Instant appliedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected JobWorkflowState() {
    }

    public static JobWorkflowState create(Job job, WorkflowStatus status, String note, Instant now) {
        if (job == null) throw new IllegalArgumentException("Job is required");
        if (status == null || !status.persisted()) {
            throw new IllegalArgumentException("Persisted workflow status is required");
        }
        JobWorkflowState state = new JobWorkflowState();
        state.job = job;
        state.status = status;
        state.note = note;
        state.appliedAt = status == WorkflowStatus.APPLIED ? now : null;
        state.createdAt = now;
        state.updatedAt = now;
        return state;
    }

    /** Returns false for a semantically identical, idempotent update. */
    public boolean update(WorkflowStatus next, String nextNote, Instant now) {
        if (next == null || !next.persisted()) {
            throw new IllegalArgumentException("Persisted workflow status is required");
        }
        if (status == next && Objects.equals(note, nextNote)) return false;
        if (next == WorkflowStatus.APPLIED && appliedAt == null) appliedAt = now;
        if (next != WorkflowStatus.APPLIED) appliedAt = null;
        status = next;
        note = nextNote;
        updatedAt = now;
        return true;
    }

    /**
     * Puts a recorded earlier state back verbatim. Mini App reversal only.
     *
     * <p>Distinct from {@link #update} because update <em>derives</em> {@code appliedAt} from
     * the target status: restoring APPLIED through it would stamp the reversal's own clock over
     * the original applied-at, and restoring anything else would silently null it. A reversal
     * puts back what was recorded — it never recomputes.
     */
    public void restore(WorkflowStatus previous, String previousNote, Instant previousAppliedAt,
                        Instant now) {
        if (previous == null || !previous.persisted()) {
            throw new IllegalArgumentException("Persisted workflow status is required");
        }
        status = previous;
        note = previousNote;
        appliedAt = previousAppliedAt;
        updatedAt = now;
    }

    public Long getJobId() { return jobId; }
    public Job getJob() { return job; }
    public WorkflowStatus getStatus() { return status; }
    public String getNote() { return note; }
    public Instant getAppliedAt() { return appliedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
