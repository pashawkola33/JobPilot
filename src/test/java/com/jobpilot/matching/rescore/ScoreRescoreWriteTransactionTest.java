package com.jobpilot.matching.rescore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.EarlyCareerEligibility;
import com.jobpilot.jobs.domain.ExperienceRequirement;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobRequirement;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.jobs.domain.JobStatus;
import com.jobpilot.jobs.domain.LocationEligibility;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RemoteScope;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.domain.ScreeningDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.domain.SeniorityLevel;
import com.jobpilot.jobs.domain.WorkplaceType;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.matching.JobMatchingService;
import com.jobpilot.matching.JobScoreCalculator;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCalculation;
import com.jobpilot.matching.ScoreCard;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport;
import com.jobpilot.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ScoreRescoreWriteTransactionTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final Instant OLD_SCORED_AT = Instant.parse("2026-08-01T00:00:00Z");

    private JobScoreRepository scores;
    private JobRequirementRepository requirements;
    private JobScoreCalculator calculator;
    private ScoreRescoreWriteTransaction transaction;

    @BeforeEach
    void setUp() {
        scores = mock(JobScoreRepository.class);
        requirements = mock(JobRequirementRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        calculator = new JobScoreCalculator(new DeterministicRequirementExtractor(),
                new JobMatchingService(clock, TestProperties.create()));
        transaction = new ScoreRescoreWriteTransaction(scores, requirements, calculator,
                new ObjectMapper().findAndRegisterModules(), clock);
    }

    @Test
    void juniorJavaFixtureWritesZeroTo55AndMiddleToJuniorInPlace() {
        Job job = job(1, "Code First Girls Programme - Junior Java Developer",
                "Build software services with Java, Spring Boot and SQL. Graduate training is "
                        + "provided. The Code First Girls mid-level accelerator programme is "
                        + "designed for women with technical experience.");
        ScoreCalculation fresh = calculator.calculate(job);
        ExtractedRequirements staleRequirements = withSeniority(fresh.requirements(), "MIDDLE");
        Target target = target(job, calculator.calculate(job, staleRequirements).score(),
                staleRequirements);
        ScoreRescorePlan plan = plan(target);
        stubLocked(List.of(target));

        ScoreRescoreWriteResult result = transaction.apply(plan);

        assertThat(result.scoreRowsUpdated()).isEqualTo(1);
        assertThat(result.requirementRowsUpdated()).isEqualTo(1);
        assertThat(target.score().getId()).isEqualTo(1_001L);
        assertThat(target.requirement().getId()).isEqualTo(2_001L);
        assertThat(target.score().toValue()).isEqualTo(fresh.score());
        assertThat(target.score().getScore()).isEqualTo(55);
        assertThat(target.score().getBand()).isEqualTo(ScoreBand.POSSIBLE_MATCH);
        assertThat(target.requirement().toValue().seniority()).isEqualTo("JUNIOR");
        assertThat(target.score().getScoredAt()).isEqualTo(NOW);
    }

    @Test
    void genuineNewMiddleSeniorityBlockerWritesPositiveToZero() {
        Job job = job(2, "Mid-Level Java Developer", "Build Java and Spring Boot services.");
        ScoreCalculation fresh = calculator.calculate(job);
        ExtractedRequirements staleRequirements = withSeniority(fresh.requirements(), "UNKNOWN");
        ScoreCard staleScore = calculator.calculate(job, staleRequirements).score();
        assertThat(staleScore.score()).isPositive();
        assertThat(fresh.score().score()).isZero();
        Target target = target(job, staleScore, staleRequirements);
        stubLocked(List.of(target));

        transaction.apply(plan(target));

        assertThat(target.score().getScore()).isZero();
        assertThat(target.score().getBand()).isEqualTo(ScoreBand.UNSUITABLE);
        assertThat(target.score().toValue().hardBlockers())
                .contains("Middle or senior seniority");
        assertThat(target.requirement().toValue().seniority()).isEqualTo("MIDDLE");
    }

    @Test
    void freshnessOnlyDifferenceUpdatesScoreButNotRequirements() {
        Job job = job(3, "Junior Java Developer", "Build Java services.");
        ScoreCalculation fresh = calculator.calculate(job);
        ScoreCard current = fresh.score();
        ScoreCard staleScore = new ScoreCard(current.score() + 1, current.band(),
                current.suitable(), current.formalEligibility(), current.javaBackend(),
                current.traineeQuality(), current.supportingTechnology(),
                current.locationFormat(), current.experienceCompatibility(),
                current.freshness() + 1, current.penalties(), current.strengths(),
                current.risks(), current.hardBlockers());
        Target target = target(job, staleScore, fresh.requirements());
        stubLocked(List.of(target));

        ScoreRescoreWriteResult result = transaction.apply(plan(target));

        assertThat(result.requirementRowsUpdated()).isZero();
        assertThat(target.score().toValue()).isEqualTo(fresh.score());
        assertThat(target.requirement().toValue()).isEqualTo(fresh.requirements());
    }

    @Test
    void changedSecondTargetAfterPlanningStopsBeforeAnyEntityMutation() {
        Job firstJob = job(4, "Junior Java Developer", "Build Java services.");
        Job secondJob = job(5, "Mid-Level Java Developer", "Build Java services.");
        ScoreCalculation firstFresh = calculator.calculate(firstJob);
        ScoreCalculation secondFresh = calculator.calculate(secondJob);
        Target first = target(firstJob, withScore(firstFresh.score(), firstFresh.score().score() - 1),
                firstFresh.requirements());
        ExtractedRequirements secondOld = withSeniority(secondFresh.requirements(), "UNKNOWN");
        Target second = target(secondJob, calculator.calculate(secondJob, secondOld).score(), secondOld);
        ScoreRescorePlan plan = plan(first, second);
        ScoreCard firstBefore = first.score().toValue();
        second.score().apply(withScore(second.score().toValue(),
                second.score().getScore() + 1), OLD_SCORED_AT);
        stubLocked(List.of(first, second));

        assertThatThrownBy(() -> transaction.apply(plan))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed after planning");

        assertThat(first.score().toValue()).isEqualTo(firstBefore);
        assertThat(first.score().getScoredAt()).isEqualTo(OLD_SCORED_AT);
        verify(requirements, never()).flush();
        verify(scores, never()).flush();
    }

    private void stubLocked(List<Target> targets) {
        List<Long> ids = targets.stream().map(target -> target.job().getId()).toList();
        when(scores.findAllByJobIdInForRescoreWrite(ids))
                .thenReturn(targets.stream().map(Target::score).toList());
        when(requirements.findAllByJobIdInForRescoreWrite(ids))
                .thenReturn(targets.stream().map(Target::requirement).toList());
    }

    private ScoreRescorePlan plan(Target... targets) {
        List<ScoreRescorePlanEntry> entries = java.util.Arrays.stream(targets)
                .map(target -> ScoreRescorePlanEntry.snapshot(target.score(), target.requirement(),
                        calculator.calculate(target.job())))
                .toList();
        var queue = new ScoreRescorePreviewReport.QueueProjection(List.of(), List.of());
        var report = new ScoreRescorePreviewReport(targets.length, 0, targets.length, 0, 0, 0,
                0, 0, Map.of(), 0, 0,
                new ScoreRescorePreviewReport.BoundaryCrossings(List.of(), List.of(), List.of()),
                List.of(), queue, queue, List.of(), null,
                new ScoreRescorePreviewReport.ChangeCounts(entries.size(), entries.size(), 0,
                        entries.size(), targets.length - entries.size()),
                List.of());
        return new ScoreRescorePlan(report, entries);
    }

    private Target target(Job job, ScoreCard score, ExtractedRequirements requirements) {
        JobScore scoreRow = new JobScore(job, score, OLD_SCORED_AT);
        JobRequirement requirementRow = new JobRequirement(job, requirements,
                String.join("|", requirements.technologies()),
                String.join("|", requirements.programmingLanguages()),
                String.join("|", requirements.spokenLanguages()),
                String.join("|", requirements.mentorshipSignals()), "{}");
        ReflectionTestUtils.setField(scoreRow, "id", 1_000L + job.getId());
        ReflectionTestUtils.setField(requirementRow, "id", 2_000L + job.getId());
        return new Target(job, scoreRow, requirementRow);
    }

    private Job job(long id, String title, String description) {
        LocationEligibilityDecision location = new LocationEligibilityDecision(
                WorkplaceType.HYBRID, LocationEligibility.BUCHAREST_LOCAL,
                RemoteScope.ROMANIA, "Bucharest", "Romania", true,
                "Bucharest role", List.of(), null, null);
        EarlyCareerDecision career = new EarlyCareerDecision(SeniorityLevel.JUNIOR,
                ExperienceRequirement.unknown(), EarlyCareerEligibility.ELIGIBLE,
                "Early-career role");
        Job job = new Job("fixture", "tenant", "external-" + id,
                "https://example.test/jobs/" + id, title, "Example", "Bucharest, Romania",
                RemoteType.HYBRID, "Full-time", description,
                Instant.parse("2026-06-01T00:00:00Z"), null, "raw", "description-" + id,
                "fingerprint-" + id, Instant.parse("2026-06-01T00:00:00Z"),
                location, career, ScreeningDecision.legacyMatch());
        ReflectionTestUtils.setField(job, "id", id);
        job.changeStatus(JobStatus.NEW);
        return job;
    }

    private ExtractedRequirements withSeniority(ExtractedRequirements value, String seniority) {
        return new ExtractedRequirements(seniority, value.internshipOrTrainee(),
                value.requiredExperienceYears(), value.requiredEducation(),
                value.finalYearMandatory(), value.technologies(), value.programmingLanguages(),
                value.spokenLanguages(), value.location(), value.remoteEligibility(),
                value.mentorshipSignals(), value.workAuthorization(), value.salary(),
                value.applicationDeadline(), value.extractionMethod());
    }

    private ScoreCard withScore(ScoreCard value, int score) {
        return new ScoreCard(score, value.band(), value.suitable(), value.formalEligibility(),
                value.javaBackend(), value.traineeQuality(), value.supportingTechnology(),
                value.locationFormat(), value.experienceCompatibility(), value.freshness(),
                value.penalties(), value.strengths(), value.risks(), value.hardBlockers());
    }

    private record Target(Job job, JobScore score, JobRequirement requirement) {
    }
}
