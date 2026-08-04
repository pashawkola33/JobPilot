package com.jobpilot.matching.preview;

import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreBand;
import java.util.List;
import java.util.Map;

/** Immutable, deterministic result; it contains no description, URL, or candidate data. */
public record ScoreRescorePreviewReport(
        int inspectedJobs,
        int exactMatches,
        int changedScoreCount,
        int changedBandCount,
        int blockerRemovedCount,
        int blockerAddedCount,
        int scoreIncreaseCount,
        int scoreDecreaseCount,
        Map<Integer, Integer> scoreDeltaDistribution,
        int storedZeroToPositiveCount,
        int storedPositiveToZeroCount,
        BoundaryCrossings boundaryCrossings,
        List<Long> matchJobsBelowReviewBecauseOfStaleScores,
        QueueProjection matchesQueue,
        QueueProjection reviewQueue,
        List<JobPreview> changedJobs,
        JobPreview juniorJavaDeveloper) {

    public record BoundaryCrossings(
            List<Long> unsuitable,
            List<Long> possibleMatch,
            List<Long> strongMatch) {
    }

    public record QueueProjection(List<QueueEntry> before, List<QueueEntry> after) {
    }

    public record QueueEntry(
            int position,
            long jobId,
            String title,
            int score,
            WorkflowStatus workflowStatus) {
    }

    public record ScoreSnapshot(
            int score,
            ScoreBand band,
            int penalties,
            List<String> penaltyReasons,
            List<String> blockers,
            String inferredSeniority) {
    }

    public record JobPreview(
            long jobId,
            String title,
            String source,
            String providerTenant,
            String externalId,
            ScreeningDisposition workflowQueue,
            WorkflowStatus workflowStatus,
            ScoreSnapshot stored,
            ScoreSnapshot computed,
            int delta,
            int rawComponentTotal,
            boolean causedBySeniorityExtractorFix,
            boolean telegramQueuePositionChanged,
            Integer oldQueuePosition,
            Integer newQueuePosition) {
    }
}
