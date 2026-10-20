package com.jobpilot.miniapp.application;

import com.jobpilot.applications.application.*;
import com.jobpilot.applications.domain.*;
import com.jobpilot.applications.repository.*;
import com.jobpilot.identity.AuthenticatedCandidate;
import com.jobpilot.identity.TelegramIdentityService;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.miniapp.api.MiniAppSnapshot;
import com.jobpilot.miniapp.repository.MiniAppStateRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated boundary shared by every v2 handler. Resource ownership is never supplied by HTTP. */
@Service
public class CandidateWorkflowService {
    private final TelegramIdentityService identities;
    private final MiniAppWorkflowService workflows;
    private final CandidateSnapshotQuery snapshots;
    private final ApplicationRepository applications;
    private final ApplicationStatusHistoryRepository histories;
    private final ApplicationTrackerService tracker;
    private final MiniAppStateRepository states;
    private final JobRepository jobs;
    private final Clock clock;

    public CandidateWorkflowService(TelegramIdentityService identities, MiniAppWorkflowService workflows,
            CandidateSnapshotQuery snapshots, ApplicationRepository applications,
            ApplicationStatusHistoryRepository histories, ApplicationTrackerService tracker,
            MiniAppStateRepository states, JobRepository jobs, Clock clock) {
        this.identities=identities; this.workflows=workflows; this.snapshots=snapshots;
        this.applications=applications; this.histories=histories; this.tracker=tracker;
        this.states=states; this.jobs=jobs; this.clock=clock;
    }

    private long candidate(AuthenticatedCandidate principal) {
        // A controller attribute alone is insufficient: revoke/expiry and forged principals fail here too.
        identities.me(principal);
        return principal.candidateId();
    }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public MiniAppSnapshot snapshot(AuthenticatedCandidate principal) {
        return snapshots.snapshot(candidate(principal));
    }

    public MiniAppOperation change(AuthenticatedCandidate principal, String mutationId, long jobId, WorkflowStatus status) {
        return workflows.change(candidate(principal), mutationId, jobId, status);
    }

    public MiniAppOperation undo(AuthenticatedCandidate principal, String mutationId, String undoToken) {
        return workflows.undo(candidate(principal), mutationId, undoToken);
    }

    @Transactional(readOnly=true)
    public ApplicationView application(AuthenticatedCandidate principal, long applicationId) {
        return tracker.view(required(candidate(principal), applicationId));
    }

    @Transactional(readOnly=true)
    public ApplicationView applicationForJob(AuthenticatedCandidate principal, long jobId) {
        long owner=candidate(principal);
        if (jobId<=0) throw notFound();
        return tracker.view(applications.findByCandidateIdAndJobId(owner,jobId)
                .orElseThrow(CandidateWorkflowService::notFound));
    }

    @Transactional(readOnly=true)
    public List<ApplicationHistoryView> history(AuthenticatedCandidate principal, long applicationId) {
        long owner=candidate(principal);
        required(owner,applicationId);
        return histories.findByCandidateIdAndApplicationIdOrderByChangedAtAscIdAsc(owner,applicationId).stream()
                .map(h -> new ApplicationHistoryView(h.getId(), h.getPreviousStatus(), h.getNewStatus(),
                        h.getChangedAt(), h.getSource())).toList();
    }

    @Transactional
    public ApplicationMutationResult notes(AuthenticatedCandidate principal, long applicationId, String notes) {
        long owner=candidate(principal);
        var revision=lock(owner);
        var application=requiredForUpdate(owner,applicationId);
        String normalized=tracker.normalize(notes,ApplicationTrackerService.MAX_NOTES_LENGTH,"Note");
        if (Objects.equals(application.getNotes(),normalized)) return new ApplicationMutationResult(tracker.view(application),false);
        application.changeNotes(normalized,clock.instant());
        applications.saveAndFlush(application);
        revision.nextRevision();
        return new ApplicationMutationResult(tracker.view(application),true);
    }

    @Transactional
    public ApplicationMutationResult followUp(AuthenticatedCandidate principal, long applicationId, LocalDate date) {
        long owner=candidate(principal);
        var revision=lock(owner);
        var application=requiredForUpdate(owner,applicationId);
        if (Objects.equals(application.getNextFollowUpDate(),date)) return new ApplicationMutationResult(tracker.view(application),false);
        application.changeFollowUpDate(date,clock.instant());
        applications.saveAndFlush(application);
        revision.nextRevision();
        return new ApplicationMutationResult(tracker.view(application),true);
    }

    @Transactional
    public ApplicationMutationResult status(AuthenticatedCandidate principal, long applicationId,
            ApplicationStatus status, java.time.Instant interviewAt, String rejectionReason) {
        long owner=candidate(principal);
        var revision=lock(owner);
        // A scalar lookup avoids caching an application before a blocked job lock: a legacy
        // writer may change notes/version while we wait. Every writer locks job before application.
        long jobId=applications.findJobIdByCandidateIdAndId(owner,applicationId).orElseThrow(CandidateWorkflowService::notFound);
        jobs.findByIdForUpdate(jobId).orElseThrow(CandidateWorkflowService::notFound);
        requiredForUpdate(owner,applicationId);
        var result=tracker.transitionInCurrentTransaction(owner,jobId,status,
                interviewAt,rejectionReason,ApplicationStatusChangeSource.INTERNAL);
        if (result.changed()) revision.nextRevision();
        return result;
    }

    private com.jobpilot.miniapp.domain.MiniAppState lock(long owner) {
        states.ensureCandidate(owner);
        return states.findByCandidateIdForUpdate(owner).orElseThrow();
    }

    private ApplicationRecord requiredForUpdate(long owner,long applicationId) {
        if(applicationId<=0) throw notFound();
        return applications.findByCandidateIdAndIdForUpdate(owner,applicationId)
                .filter(application -> Objects.equals(application.getCandidateId(),owner))
                .orElseThrow(CandidateWorkflowService::notFound);
    }

    private ApplicationRecord required(long owner,long applicationId) {
        if(applicationId<=0) throw notFound();
        var application=applications.findByCandidateIdAndId(owner,applicationId).orElseThrow(CandidateWorkflowService::notFound);
        if (!Objects.equals(application.getCandidateId(),owner)) throw notFound();
        return application;
    }
    private static ApplicationTrackingException notFound() {
        return new ApplicationTrackingException(ApplicationTrackingException.Category.APPLICATION_NOT_FOUND,"Application was not found.");
    }
}
