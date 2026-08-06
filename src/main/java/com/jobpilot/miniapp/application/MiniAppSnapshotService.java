package com.jobpilot.miniapp.application;

import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.application.ApplicationView;
import com.jobpilot.jobreview.repository.JobReviewQueryRepository;
import com.jobpilot.jobreview.repository.JobReviewQueryRepository.MiniAppJobRow;
import com.jobpilot.miniapp.api.MiniAppSnapshot;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppApplication;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppJob;
import com.jobpilot.jobreview.application.JobQueueItem;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects the existing read models into the Mini App contract. It owns no domain rules. */
@Service
public class MiniAppSnapshotService {
    /**
     * One session's worth of triage. Deliberately not paginated: the Mini App reviews a
     * queue rather than browsing an archive, and an unbounded read is the failure mode
     * worth designing out. Add paging here if a real queue ever outgrows this.
     */
    public static final int MAX_JOBS = 50;

    private final JobReviewQueryRepository queries;
    private final ApplicationTrackerService applications;

    public MiniAppSnapshotService(JobReviewQueryRepository queries,
                                  ApplicationTrackerService applications) {
        this.queries = queries;
        this.applications = applications;
    }

    @Transactional(readOnly = true)
    public MiniAppSnapshot snapshot() {
        List<MiniAppJob> jobs = queries.findMiniAppJobs(MAX_JOBS).stream()
                .map(MiniAppSnapshotService::job).toList();
        // ApplicationTrackerService.list already bounds itself to the newest 20.
        List<MiniAppApplication> tracked = applications.list(null).stream()
                .map(MiniAppSnapshotService::application).toList();
        return new MiniAppSnapshot(jobs, tracked);
    }

    private static MiniAppJob job(MiniAppJobRow row) {
        JobQueueItem item = row.item();
        return new MiniAppJob(item.id(), item.title(), item.company(), item.location(),
                row.remoteType(), row.seniorityLevel(), row.employmentType(),
                item.score(), row.band(), item.disposition(), item.workflowStatus(),
                item.source(), item.publishedAt(), item.canonicalUrl(),
                row.strengths(), row.risks());
    }

    private static MiniAppApplication application(ApplicationView view) {
        return new MiniAppApplication(view.jobId(), view.title(), view.company(), view.status(),
                view.canonicalUrl(), view.updatedAt(), view.applicationDate(),
                view.nextFollowUpDate() == null ? null : view.nextFollowUpDate().toString());
    }
}
