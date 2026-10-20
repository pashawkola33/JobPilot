package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.repository.*;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.miniapp.application.*;
import com.jobpilot.miniapp.repository.MiniAppStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateWorkflowBoundaryTest {
    @Test void everyEntryPointRevalidatesPrincipalBeforePrivateStorage() {
        var identities=mock(TelegramIdentityService.class);
        var workflows=mock(MiniAppWorkflowService.class);
        var snapshots=mock(CandidateSnapshotQuery.class);
        var applications=mock(ApplicationRepository.class);
        var histories=mock(ApplicationStatusHistoryRepository.class);
        var tracker=mock(ApplicationTrackerService.class);
        var states=mock(MiniAppStateRepository.class);
        var jobs=mock(JobRepository.class);
        var service=new CandidateWorkflowService(identities,workflows,snapshots,applications,histories,tracker,states,jobs,Clock.systemUTC());
        var expired=new AuthenticatedCandidate(42,9002,Instant.EPOCH,"synthetic");
        for (AuthenticatedCandidate principal : java.util.Arrays.asList(null,expired)) {
            doThrow(new IdentityException(401,"UNAUTHENTICATED")).when(identities).me(principal);
            List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls=List.of(
                    ()->service.snapshot(principal), ()->service.change(principal,"key",100,WorkflowStatus.SAVED),
                    ()->service.undo(principal,"key","token"), ()->service.application(principal,7),
                    ()->service.applicationForJob(principal,100),
                    ()->service.history(principal,7), ()->service.notes(principal,7,"notes"),
                    ()->service.followUp(principal,7,null), ()->service.status(principal,7,ApplicationStatus.APPLIED,null,null));
            calls.forEach(call->assertThatThrownBy(call).isInstanceOf(IdentityException.class));
        }
        verifyNoInteractions(workflows,snapshots,applications,histories,tracker,states,jobs);
    }
}
