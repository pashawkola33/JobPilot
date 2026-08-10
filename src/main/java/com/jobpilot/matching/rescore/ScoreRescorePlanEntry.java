package com.jobpilot.matching.rescore;

import com.jobpilot.common.Hashing;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobRequirement;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreCalculation;
import com.jobpilot.matching.ScoreCard;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Complete immutable old/new snapshot for one changed persisted score projection. */
public record ScoreRescorePlanEntry(
        long jobId,
        long scoreRowId,
        long requirementRowId,
        ScreeningDisposition screeningDisposition,
        String descriptionHash,
        String sourceContentHash,
        Instant storedScoredAt,
        String storedRequirementJsonHash,
        ScoreCard storedScore,
        ExtractedRequirements storedRequirements,
        ScoreCard computedScore,
        ExtractedRequirements computedRequirements) {

    public ScoreRescorePlanEntry {
        if (jobId < 1 || scoreRowId < 1 || requirementRowId < 1) {
            throw new IllegalArgumentException("Persisted rescore row identities are required");
        }
        Objects.requireNonNull(screeningDisposition, "Screening disposition is required");
        descriptionHash = requireText(descriptionHash, "Description hash");
        sourceContentHash = requireText(sourceContentHash, "Source content hash");
        Objects.requireNonNull(storedScoredAt, "Stored score timestamp is required");
        storedScore = immutable(storedScore);
        storedRequirements = immutable(storedRequirements);
        computedScore = immutable(computedScore);
        computedRequirements = immutable(computedRequirements);
    }

    public static ScoreRescorePlanEntry snapshot(JobScore scoreRow,
                                                 JobRequirement requirementRow,
                                                 ScoreCalculation computed) {
        Objects.requireNonNull(scoreRow, "Score row is required");
        Objects.requireNonNull(requirementRow, "Requirements row is required");
        Objects.requireNonNull(computed, "Computed score is required");
        Job job = Objects.requireNonNull(scoreRow.getJob(), "Score job is required");
        if (requirementRow.getJob() == null
                || !Objects.equals(job.getId(), requirementRow.getJob().getId())) {
            throw new IllegalArgumentException("Score and requirements jobs must match");
        }
        String rawJson = requirementRow.getRawJson();
        return new ScoreRescorePlanEntry(requireId(job.getId(), "job"),
                requireId(scoreRow.getId(), "score"),
                requireId(requirementRow.getId(), "requirements"),
                job.getScreeningDisposition(), job.getDescriptionHash(),
                Hashing.sha256(job.getTitle() + "\u0000" + job.getDescription()),
                scoreRow.getScoredAt(), rawJson == null ? null : Hashing.sha256(rawJson),
                scoreRow.toValue(), requirementRow.toValue(),
                computed.score(), computed.requirements());
    }

    /**
     * A row is planned when either persisted projection would move. The write transaction already
     * re-persists requirements whenever they differ, so a score-only test let it change rows the
     * preview never showed, while requirement drift carrying no score change — a stale seniority,
     * a withdrawn dirty technology token — stayed permanently unreachable.
     */
    public boolean changed() {
        return !storedScore.equals(computedScore)
                || !storedRequirements.equals(computedRequirements);
    }

    public boolean scoreChanged() {
        return !storedScore.equals(computedScore);
    }

    public boolean requirementsChanged() {
        return !storedRequirements.equals(computedRequirements);
    }

    private static long requireId(Long id, String type) {
        if (id == null || id < 1) {
            throw new IllegalArgumentException("Persisted " + type + " identity is required");
        }
        return id;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static ScoreCard immutable(ScoreCard value) {
        Objects.requireNonNull(value, "Score card is required");
        return new ScoreCard(value.score(), Objects.requireNonNull(value.band()), value.suitable(),
                value.formalEligibility(), value.javaBackend(), value.traineeQuality(),
                value.supportingTechnology(), value.locationFormat(),
                value.experienceCompatibility(), value.freshness(), value.penalties(),
                copy(value.strengths()), copy(value.risks()), copy(value.hardBlockers()));
    }

    private static ExtractedRequirements immutable(ExtractedRequirements value) {
        Objects.requireNonNull(value, "Requirements are required");
        return new ExtractedRequirements(value.seniority(), value.internshipOrTrainee(),
                value.requiredExperienceYears(), value.requiredEducation(),
                value.finalYearMandatory(), copy(value.technologies()),
                copy(value.programmingLanguages()), copy(value.spokenLanguages()),
                value.location(), value.remoteEligibility(), copy(value.mentorshipSignals()),
                value.workAuthorization(), value.salary(), value.applicationDeadline(),
                value.extractionMethod());
    }

    private static List<String> copy(List<String> values) {
        return List.copyOf(Objects.requireNonNull(values, "Persisted list is required"));
    }
}
