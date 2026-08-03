package com.jobpilot.jobreview.application;

import com.jobpilot.jobreview.domain.JobWorkflowState;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobreview.repository.JobReviewQueryRepository;
import com.jobpilot.jobreview.repository.JobWorkflowStateRepository;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.repository.JobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery-neutral review workflow. Nothing here knows about Telegram, HTTP, or HTML;
 * the bot is one caller and the only one today.
 */
@Service
public class JobReviewService {
    /** Hard ceiling enforced by the job_workflow_state note check constraint. */
    public static final int MAX_NOTE_LENGTH = 1000;
    public static final int MAX_PAGE_SIZE = 20;
    public static final int MAX_PAGE_INDEX = 999;

    private final JobReviewQueryRepository queries;
    private final JobWorkflowStateRepository workflowStates;
    private final JobRepository jobs;
    private final Clock clock;

    public JobReviewService(JobReviewQueryRepository queries,
                            JobWorkflowStateRepository workflowStates,
                            JobRepository jobs,
                            Clock clock) {
        this.queries = queries;
        this.workflowStates = workflowStates;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public JobQueuePage page(JobQueue queue, int page, int size) {
        int boundedSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int boundedPage = Math.clamp(page, 0, MAX_PAGE_INDEX);
        return new JobQueuePage(queue, queries.findQueue(queue, boundedPage, boundedSize),
                boundedPage, boundedSize, queries.count(queue));
    }

    @Transactional(readOnly = true)
    public JobDetailView detail(long jobId) {
        if (jobId <= 0) throw notFound(jobId);
        return queries.findDetail(jobId).orElseThrow(() -> notFound(jobId));
    }

    @Transactional(readOnly = true)
    public JobReviewStats stats() {
        return queries.stats();
    }

    @Transactional(readOnly = true)
    public List<JobQueueItem> notifiable(ScreeningDisposition disposition, Collection<Long> jobIds) {
        return queries.findNotifiable(disposition, jobIds);
    }

    @Transactional
    public WorkflowView save(long jobId) {
        return transition(jobId, WorkflowStatus.SAVED);
    }

    @Transactional
    public WorkflowView applied(long jobId) {
        return transition(jobId, WorkflowStatus.APPLIED);
    }

    @Transactional
    public WorkflowView dismiss(long jobId) {
        return transition(jobId, WorkflowStatus.DISMISSED);
    }

    /** Back to implicit UNREVIEWED by deleting the row; repeating it is a no-op. */
    @Transactional
    public WorkflowView reset(long jobId) {
        requireJob(jobId);
        JobWorkflowState existing = workflowStates.findByJobIdForUpdate(jobId).orElse(null);
        if (existing != null) {
            workflowStates.delete(existing);
            // Flush so the queue read model, which goes through JDBC, sees the deletion.
            workflowStates.flush();
        }
        return new WorkflowView(jobId, WorkflowStatus.UNREVIEWED, null, null, null,
                existing != null);
    }

    /**
     * Adds, replaces, or clears the note. A note on an implicitly UNREVIEWED vacancy also
     * saves it, because job_workflow_state has no row to hold a note otherwise.
     */
    @Transactional
    public WorkflowView note(long jobId, String note) {
        String normalized = normalizeNote(note);
        Job job = requireJob(jobId);
        Instant now = clock.instant();
        JobWorkflowState existing = workflowStates.findByJobIdForUpdate(jobId).orElse(null);
        if (existing == null) {
            if (normalized == null) {
                return new WorkflowView(jobId, WorkflowStatus.UNREVIEWED, null, null, null, false);
            }
            return view(workflowStates.saveAndFlush(
                    JobWorkflowState.create(job, WorkflowStatus.SAVED, normalized, now)), true);
        }
        boolean changed = existing.update(existing.getStatus(), normalized, now);
        if (changed) workflowStates.flush();
        return view(existing, changed);
    }

    private WorkflowView transition(long jobId, WorkflowStatus status) {
        Job job = requireJob(jobId);
        Instant now = clock.instant();
        JobWorkflowState existing = workflowStates.findByJobIdForUpdate(jobId).orElse(null);
        if (existing == null) {
            return view(workflowStates.saveAndFlush(
                    JobWorkflowState.create(job, status, null, now)), true);
        }
        // Repeating an action keeps the existing note and reports no change.
        boolean changed = existing.update(status, existing.getNote(), now);
        if (changed) workflowStates.flush();
        return view(existing, changed);
    }

    private Job requireJob(long jobId) {
        if (jobId <= 0) throw notFound(jobId);
        return jobs.findByIdForUpdate(jobId).orElseThrow(() -> notFound(jobId));
    }

    private String normalizeNote(String note) {
        if (note == null || note.isBlank()) return null;
        String normalized = note.strip().replace("\u0000", "").strip();
        if (normalized.length() > MAX_NOTE_LENGTH) {
            throw new JobReviewException(JobReviewException.Category.INVALID_WORKFLOW,
                    "Note must contain at most " + MAX_NOTE_LENGTH + " characters.");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private WorkflowView view(JobWorkflowState state, boolean changed) {
        return new WorkflowView(state.getJobId(), state.getStatus(), state.getNote(),
                state.getAppliedAt(), state.getUpdatedAt(), changed);
    }

    private JobReviewException notFound(long jobId) {
        return new JobReviewException(JobReviewException.Category.JOB_NOT_FOUND,
                "That vacancy is not in the review queue.");
    }
}
