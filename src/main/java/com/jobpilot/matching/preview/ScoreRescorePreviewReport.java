package com.jobpilot.matching.preview;

import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreBand;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, deterministic result; it contains no description, URL, or candidate data. */
public record ScoreRescorePreviewReport(
        int inspectedJobs,
        int exactMatches,
        int scoreDeltaChangedCount,
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
        JobPreview juniorJavaDeveloper,
        ChangeCounts changeCounts,
        List<RequirementsPreview> requirementsChangedJobs) {

    /**
     * Plan membership, decomposed. {@code scoreDeltaChangedCount} above counts a numeric score
     * move; {@code scoreChangedCount} here counts a {@code ScoreCard} identity change, which is
     * what actually decides whether a row is written.
     */
    public record ChangeCounts(
            int scoreChangedCount,
            int requirementsChangedCount,
            int requirementsOnlyChangedCount,
            int changedPlanCount,
            int unchangedCount) {
    }

    /** Every persisted component of {@code ExtractedRequirements}, in record order. */
    public enum RequirementField {
        SENIORITY,
        INTERNSHIP_OR_TRAINEE,
        REQUIRED_EXPERIENCE_YEARS,
        REQUIRED_EDUCATION,
        FINAL_YEAR_MANDATORY,
        TECHNOLOGIES,
        PROGRAMMING_LANGUAGES,
        SPOKEN_LANGUAGES,
        LOCATION,
        REMOTE_ELIGIBILITY,
        MENTORSHIP_SIGNALS,
        WORK_AUTHORIZATION,
        SALARY,
        APPLICATION_DEADLINE,
        EXTRACTION_METHOD
    }

    /** One differing persisted field. Both values arrive already sanitized and bounded. */
    public record RequirementFieldChange(
            RequirementField field,
            String storedValue,
            String computedValue) {

        public static final int MAX_VALUE_LENGTH = 80;

        public RequirementFieldChange {
            Objects.requireNonNull(field, "Requirement field is required");
            requireBounded(storedValue, "Stored");
            requireBounded(computedValue, "Computed");
        }

        private static void requireBounded(String value, String side) {
            if (value == null || value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        side + " requirement value must be sanitized and bounded");
            }
        }
    }

    /**
     * Why one row entered the plan. Carries no description, URL, or candidate data, and cannot be
     * constructed without naming at least one field, so a planned row can never be invisible.
     */
    public record RequirementsPreview(
            long jobId,
            String title,
            boolean scoreChanged,
            int storedScore,
            int computedScore,
            List<RequirementFieldChange> changedFields) {

        public RequirementsPreview {
            changedFields = List.copyOf(changedFields);
            if (changedFields.isEmpty()) {
                throw new IllegalArgumentException(
                        "A requirements preview must name at least one changed field");
            }
        }
    }

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
