package com.jobpilot.applications.application;

import com.jobpilot.applications.domain.ApplicationRecord;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import com.jobpilot.applications.domain.ApplicationStatusHistory;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.applications.repository.ApplicationStatusHistoryRepository;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.repository.JobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApplicationTrackerService {
    public static final int MAX_NOTES_LENGTH = 2000;
    public static final int MAX_REJECTION_REASON_LENGTH = 1000;
    /** Shared with callers that run their own retry around a whole caller-owned transaction. */
    public static final int MAX_CONFLICT_ATTEMPTS = 3;

    /** The one conflict exception every retry path exhausts into, so the 409 contract is uniform. */
    public static ApplicationTrackingException conflict() {
        return new ApplicationTrackingException(ApplicationTrackingException.Category.CONFLICT,
                "The application changed concurrently. Please retry.");
    }

    private final ApplicationRepository applications;
    private final ApplicationStatusHistoryRepository history;
    private final JobRepository jobs;
    private final ApplicationTransitionPolicy transitions;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ApplicationTrackerService(ApplicationRepository applications,
                                     ApplicationStatusHistoryRepository history,
                                     JobRepository jobs,
                                     ApplicationTransitionPolicy transitions,
                                     Clock clock,
                                     PlatformTransactionManager transactionManager) {
        this.applications = applications;
        this.history = history;
        this.jobs = jobs;
        this.transitions = transitions;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * The standalone entry point: owns its transaction and retries conflicts itself. Every
     * attempt begins a transaction of its own, so a rolled-back attempt never leaks into the
     * next one.
     *
     * <p>Callers that already hold a transaction must use
     * {@link #transitionInCurrentTransaction} instead — retrying inside a caller's transaction
     * cannot work, because the failed attempt has already marked that transaction rollback-only.
     */
    public ApplicationMutationResult transition(long jobId, ApplicationStatus requested,
                                                Instant interviewAt, String rejectionReason,
                                                ApplicationStatusChangeSource source) {
        String reason = validated(requested, interviewAt, rejectionReason, source);
        return retryConflicts(() -> transactions.execute(
                status -> transitionOnce(jobId, requested, interviewAt, reason, source)));
    }

    /**
     * Exactly one attempt, joined to the transaction the caller already owns, with no retry of
     * its own. A conflict propagates to the caller, whose job it is to abandon the whole
     * transaction and start a fresh one — see MiniAppWorkflowService.
     */
    public ApplicationMutationResult transitionInCurrentTransaction(
            long jobId, ApplicationStatus requested, Instant interviewAt,
            String rejectionReason, ApplicationStatusChangeSource source) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("transitionInCurrentTransaction requires an active "
                    + "transaction; standalone callers must use transition(...)");
        }
        String reason = validated(requested, interviewAt, rejectionReason, source);
        return transitionOnce(jobId, requested, interviewAt, reason, source);
    }

    /** Shared argument checks. Returns the normalized rejection reason. */
    private String validated(ApplicationStatus requested, Instant interviewAt,
                             String rejectionReason, ApplicationStatusChangeSource source) {
        if (requested == null || source == null) throw invalid("Status and source are required.");
        String reason = normalize(rejectionReason, MAX_REJECTION_REASON_LENGTH, "Rejection reason");
        if (requested == ApplicationStatus.INTERVIEW && interviewAt == null) {
            throw invalid("Interview date and UTC offset are required.");
        }
        return reason;
    }

    /** One transition against whichever transaction is current. Never retries. */
    private ApplicationMutationResult transitionOnce(long jobId, ApplicationStatus requested,
                                                     Instant interviewAt, String reason,
                                                     ApplicationStatusChangeSource source) {
        Instant now = clock.instant();
        Job job = jobs.findById(jobId).orElseThrow(() -> new ApplicationTrackingException(
                ApplicationTrackingException.Category.JOB_NOT_FOUND, "Job was not found."));
        ApplicationRecord existing = applications.findByJobId(jobId).orElse(null);
        if (existing == null) {
            if (!transitions.canCreate(requested)) {
                throw new ApplicationTrackingException(
                        ApplicationTrackingException.Category.INVALID_TRANSITION,
                        "Create the application as SAVED or APPLIED first.");
            }
            ApplicationRecord created = ApplicationRecord.create(job, requested, now);
            created = applications.saveAndFlush(created);
            history.save(new ApplicationStatusHistory(
                    created, null, requested, now, source));
            return new ApplicationMutationResult(view(created), true);
        }
        ApplicationStatus previous = existing.getStatus();
        if (previous == requested) {
            if (requested != ApplicationStatus.INTERVIEW
                    || Objects.equals(existing.getInterviewDate(), interviewAt)) {
                return new ApplicationMutationResult(view(existing), false);
            }
        }
        if (!transitions.canTransition(previous, requested)) {
            throw new ApplicationTrackingException(
                    ApplicationTrackingException.Category.INVALID_TRANSITION,
                    "That application status transition is not allowed.");
        }
        existing.transitionTo(requested, interviewAt, reason, now);
        applications.saveAndFlush(existing);
        history.save(new ApplicationStatusHistory(
                existing, previous, requested, now, source));
        return new ApplicationMutationResult(view(existing), true);
    }

    public ApplicationMutationResult changeFollowUpDate(long jobId, LocalDate date) {
        return retryConflicts(() -> transactions.execute(status -> {
            ApplicationRecord application = requiredApplication(jobId);
            if (java.util.Objects.equals(application.getNextFollowUpDate(), date)) {
                return new ApplicationMutationResult(view(application), false);
            }
            application.changeFollowUpDate(date, clock.instant());
            applications.saveAndFlush(application);
            return new ApplicationMutationResult(view(application), true);
        }));
    }

    public ApplicationMutationResult changeNotes(long jobId, String notes) {
        String normalized = normalize(notes, MAX_NOTES_LENGTH, "Note");
        return retryConflicts(() -> transactions.execute(status -> {
            ApplicationRecord application = requiredApplication(jobId);
            if (java.util.Objects.equals(application.getNotes(), normalized)) {
                return new ApplicationMutationResult(view(application), false);
            }
            application.changeNotes(normalized, clock.instant());
            applications.saveAndFlush(application);
            return new ApplicationMutationResult(view(application), true);
        }));
    }

    public ApplicationView findByJobId(long jobId) {
        return transactions.execute(status -> view(requiredApplication(jobId)));
    }

    public List<ApplicationView> list(ApplicationStatus filter) {
        return transactions.execute(status -> {
            var page = PageRequest.of(0, 20);
            List<ApplicationRecord> records = filter == null
                    ? applications.findAllByOrderByUpdatedAtDesc(page)
                    : applications.findByStatusOrderByUpdatedAtDesc(filter, page);
            return records.stream().map(this::view).toList();
        });
    }

    /**
     * One bounded Mini App page plus totals that describe the complete durable collection.
     * The enclosing Mini App snapshot transaction is joined when this is called from there.
     */
    public ApplicationSnapshotView snapshot(int requestedLimit) {
        int limit = Math.clamp(requestedLimit, 1, 100);
        return transactions.execute(status -> {
            List<ApplicationView> items = applications
                    .findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit)).stream()
                    .map(this::view).toList();
            long total = applications.count();
            return new ApplicationSnapshotView(items, total, limit,
                    new ApplicationSnapshotView.Counts(
                            total,
                            applications.countByStatus(ApplicationStatus.SAVED),
                            applications.countByStatus(ApplicationStatus.APPLIED),
                            applications.countByStatus(ApplicationStatus.INTERVIEW),
                            applications.countByStatus(ApplicationStatus.OFFER),
                            applications.countByStatus(ApplicationStatus.REJECTED),
                            applications.countByStatus(ApplicationStatus.WITHDRAWN)));
        });
    }

    public List<ApplicationHistoryView> history(long jobId) {
        return transactions.execute(status -> {
            ApplicationRecord application = requiredApplication(jobId);
            return history.findByApplicationIdOrderByChangedAtAscIdAsc(application.getId()).stream()
                    .map(value -> new ApplicationHistoryView(value.getId(),
                            value.getPreviousStatus(), value.getNewStatus(), value.getChangedAt(),
                            value.getSource()))
                    .toList();
        });
    }

    private ApplicationRecord requiredApplication(long jobId) {
        return applications.findByJobId(jobId).orElseThrow(() -> new ApplicationTrackingException(
                ApplicationTrackingException.Category.APPLICATION_NOT_FOUND,
                "No application exists for that job."));
    }

    private ApplicationView view(ApplicationRecord application) {
        Job job = application.getJob();
        return new ApplicationView(application.getId(), job.getId(), job.getTitle(), job.getCompany(),
                job.getCanonicalUrl(), application.getStatus(), application.getApplicationDate(),
                application.getInterviewDate(), application.getNextFollowUpDate(), application.getNotes(),
                application.getRejectionReason(), application.getUpdatedAt());
    }

    private <T> T retryConflicts(Supplier<T> operation) {
        for (int attempt = 1; attempt <= MAX_CONFLICT_ATTEMPTS; attempt++) {
            try {
                return operation.get();
            } catch (DataIntegrityViolationException | OptimisticLockingFailureException conflict) {
                if (attempt == MAX_CONFLICT_ATTEMPTS) throw conflict();
            }
        }
        throw new IllegalStateException("Unreachable conflict retry state");
    }

    private String normalize(String value, int limit, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().replaceAll("\\s+", " ");
        if (normalized.length() > limit) throw invalid(label + " is too long.");
        return normalized;
    }

    private ApplicationTrackingException invalid(String message) {
        return new ApplicationTrackingException(
                ApplicationTrackingException.Category.INVALID_VALUE, message);
    }
}
