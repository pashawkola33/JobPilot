package com.jobpilot.miniapp.api;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import java.time.Instant;
import java.util.List;

/**
 * Authoritative Mini App read model. Review, saved, and application rows have independent
 * bounded windows; every window carries its complete durable total so it is never mistaken
 * for the whole collection.
 */
public record MiniAppSnapshot(
        MiniAppJobPage reviewQueue,
        MiniAppJobPage saved,
        MiniAppApplicationPage applications,
        MiniAppWorkflowCounts workflowCounts,
        MiniAppApplicationCounts applicationCounts) {

    public record MiniAppJobPage(List<MiniAppJob> items, long total, int limit, boolean truncated) {
        public MiniAppJobPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record MiniAppApplicationPage(
            List<MiniAppApplication> items, long total, int limit, boolean truncated) {
        public MiniAppApplicationPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record MiniAppWorkflowCounts(
            long unreviewedMatch,
            long unreviewedReview,
            long saved,
            long applied,
            long dismissed) {
    }

    public record MiniAppApplicationCounts(
            long total,
            long saved,
            long applied,
            long interview,
            long offer,
            long rejected,
            long withdrawn) {
    }

    public record MiniAppJob(
            long id,
            String title,
            String company,
            String location,
            String remoteType,
            String seniority,
            String employmentType,
            int score,
            String band,
            ScreeningDisposition disposition,
            WorkflowStatus workflowStatus,
            String source,
            Instant publishedAt,
            String canonicalUrl,
            List<String> strengths,
            List<String> risks) {
    }

    public record MiniAppApplication(
            long jobId,
            String title,
            String company,
            ApplicationStatus status,
            String canonicalUrl,
            Instant updatedAt,
            Instant appliedAt,
            String nextFollowUpDate,
            MiniAppJob job) {
    }
}
