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
 *
 * @param mutationRevision the Mini App mutation count this snapshot was taken after — an
 *     <em>as-of</em> marker and nothing more. It does not order snapshots against each other:
 *     ingestion, the Telegram command path and ApplicationController all change the data below
 *     without advancing it, so two genuinely different states can carry the same number.
 *     Clients order reads by their own read generation and must never compare this field to
 *     decide which of two responses is newer. See docs/mini-app-p0b-consistency-model.md.
 */
public record MiniAppSnapshot(
        long mutationRevision,
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
