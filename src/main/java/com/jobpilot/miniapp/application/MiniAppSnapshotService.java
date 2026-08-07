package com.jobpilot.miniapp.application;

import com.jobpilot.applications.application.ApplicationSnapshotView;
import com.jobpilot.applications.application.ApplicationTrackerService;
import com.jobpilot.applications.application.ApplicationView;
import com.jobpilot.jobreview.application.JobQueueItem;
import com.jobpilot.jobreview.application.JobReviewStats;
import com.jobpilot.jobreview.repository.JobReviewQueryRepository;
import com.jobpilot.jobreview.repository.JobReviewQueryRepository.MiniAppJobRow;
import com.jobpilot.miniapp.api.MiniAppSnapshot;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppApplication;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppApplicationCounts;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppApplicationPage;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppJob;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppJobPage;
import com.jobpilot.miniapp.api.MiniAppSnapshot.MiniAppWorkflowCounts;
import com.jobpilot.miniapp.domain.MiniAppState;
import com.jobpilot.miniapp.repository.MiniAppStateRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Projects existing durable read models into the Mini App contract. It owns no domain rules. */
@Service
public class MiniAppSnapshotService {
    public static final int MAX_REVIEW_JOBS = 50;
    public static final int MAX_SAVED_JOBS = 50;
    public static final int MAX_APPLICATIONS = 20;

    private final JobReviewQueryRepository queries;
    private final ApplicationTrackerService applications;
    private final MiniAppStateRepository states;

    public MiniAppSnapshotService(JobReviewQueryRepository queries,
                                  ApplicationTrackerService applications,
                                  MiniAppStateRepository states) {
        this.queries = queries;
        this.applications = applications;
        this.states = states;
    }

    /**
     * The authoritative read, and the <em>only</em> path that may replace global projections.
     *
     * <p>REPEATABLE READ is load-bearing here, not decoration. This method issues roughly a
     * dozen statements; at the default READ COMMITTED each one sees a fresh database moment, so
     * a commit landing midway through is half-visible — counts from after it, rows from before —
     * and the response describes a state that never existed. One snapshot must be one moment.
     *
     * <p>It takes no locks and never touches {@code mini_app_state}: a read must not queue
     * behind a mutation, and ordering reads against each other is the client read generation's
     * job. The mutation path deliberately runs at READ COMMITTED for the opposite reason — see
     * {@link MiniAppWorkflowService} on why copying this isolation there would break duplicate
     * handling.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MiniAppSnapshot snapshot() {
        JobReviewStats workflow = queries.stats();
        List<MiniAppJob> review = queries.findMiniAppReviewJobs(MAX_REVIEW_JOBS).stream()
                .map(MiniAppSnapshotService::job).toList();
        List<MiniAppJob> saved = queries.findMiniAppSavedJobs(MAX_SAVED_JOBS).stream()
                .map(MiniAppSnapshotService::job).toList();
        ApplicationSnapshotView tracked = applications.snapshot(MAX_APPLICATIONS);
        Map<Long, MiniAppJob> applicationJobs = queries.findMiniAppJobsByIds(
                        tracked.items().stream().map(ApplicationView::jobId).toList()).stream()
                .map(MiniAppSnapshotService::job)
                .collect(Collectors.toMap(MiniAppJob::id, Function.identity()));
        List<MiniAppApplication> applicationItems = tracked.items().stream()
                .map(view -> application(view, applicationJobs.get(view.jobId()))).toList();
        long reviewTotal = workflow.unreviewedMatch() + workflow.unreviewedReview();
        ApplicationSnapshotView.Counts counts = tracked.counts();
        return new MiniAppSnapshot(
                // Read, never locked: a snapshot must not queue behind a mutation. Inside this
                // REPEATABLE READ transaction it is the same moment as every row above.
                states.findById(MiniAppState.SINGLETON_ID)
                        .map(MiniAppState::getMutationRevision).orElse(0L),
                new MiniAppJobPage(review, reviewTotal, MAX_REVIEW_JOBS,
                        reviewTotal > review.size()),
                new MiniAppJobPage(saved, workflow.saved(), MAX_SAVED_JOBS,
                        workflow.saved() > saved.size()),
                new MiniAppApplicationPage(applicationItems, tracked.total(), tracked.limit(),
                        tracked.truncated()),
                new MiniAppWorkflowCounts(workflow.unreviewedMatch(), workflow.unreviewedReview(),
                        workflow.saved(), workflow.applied(), workflow.dismissed()),
                new MiniAppApplicationCounts(counts.total(), counts.saved(), counts.applied(),
                        counts.interview(), counts.offer(), counts.rejected(), counts.withdrawn()));
    }

    private static MiniAppJob job(MiniAppJobRow row) {
        JobQueueItem item = row.item();
        return new MiniAppJob(item.id(), item.title(), item.company(), item.location(),
                row.remoteType(), row.seniorityLevel(), row.employmentType(),
                item.score(), row.band(), item.disposition(), item.workflowStatus(),
                item.source(), item.publishedAt(), item.canonicalUrl(),
                row.strengths(), row.risks());
    }

    private static MiniAppApplication application(ApplicationView view, MiniAppJob job) {
        return new MiniAppApplication(view.jobId(), view.title(), view.company(), view.status(),
                view.canonicalUrl(), view.updatedAt(), view.applicationDate(),
                view.nextFollowUpDate() == null ? null : view.nextFollowUpDate().toString(), job);
    }
}
