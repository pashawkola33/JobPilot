package com.jobpilot.matching.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobreview.repository.JobWorkflowStateRepository;
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
import com.jobpilot.matching.ScoreCalculation;
import com.jobpilot.matching.ScoreCard;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.rescore.ScoreRescorePlanResult;
import com.jobpilot.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class ScoreRescorePreviewServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final Set<String> WRITE_METHODS = Set.of(
            "save", "saveAll", "saveAndFlush", "saveAllAndFlush", "delete", "deleteAll",
            "deleteAllInBatch", "deleteAllById", "deleteAllByIdInBatch", "deleteById", "flush");

    private JobScoreRepository scores;
    private JobRequirementRepository requirements;
    private JobWorkflowStateRepository workflow;
    private JobScoreCalculator calculator;
    private ScoreRescorePreviewService service;

    @BeforeEach
    void setUp() {
        scores = mock(JobScoreRepository.class);
        requirements = mock(JobRequirementRepository.class);
        workflow = mock(JobWorkflowStateRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        calculator = new JobScoreCalculator(new DeterministicRequirementExtractor(),
                new JobMatchingService(clock, TestProperties.create()));
        service = new ScoreRescorePreviewService(scores, requirements, workflow, calculator);
    }

    @Test
    void juniorJavaDeveloperPreviewsZeroTo55WithSeniorityBlockerRemovedAndQueueMove() {
        Job target = juniorProgramme(1L);
        ScoreCalculation fresh = calculator.calculate(target);
        assertThat(fresh.score().score()).isEqualTo(55);
        ExtractedRequirements staleRequirements = withSeniority(fresh.requirements(), "MIDDLE");
        ScoreCard staleScore = calculator.calculate(target, staleRequirements).score();
        assertThat(staleScore.score()).isZero();

        Job comparison = ordinaryMatch(2L);
        ScoreCalculation comparisonScore = calculator.calculate(comparison);
        assertThat(comparisonScore.score().score()).isBetween(1, 55);
        stub(List.of(row(target, staleScore), row(comparison, comparisonScore.score())),
                List.of(requirement(target, staleRequirements),
                        requirement(comparison, comparisonScore.requirements())));

        ScoreRescorePreviewResult result = service.preview(250);

        assertThat(result.status()).isEqualTo(ScoreRescorePreviewResult.Status.SUCCESS);
        var targetResult = result.report().juniorJavaDeveloper();
        assertThat(targetResult).isNotNull();
        assertThat(targetResult.stored().score()).isZero();
        assertThat(targetResult.stored().band()).isEqualTo(ScoreBand.UNSUITABLE);
        assertThat(targetResult.computed().score()).isEqualTo(55);
        assertThat(targetResult.computed().band()).isEqualTo(ScoreBand.POSSIBLE_MATCH);
        assertThat(targetResult.delta()).isEqualTo(55);
        assertThat(targetResult.stored().blockers()).contains("Middle or senior seniority");
        assertThat(targetResult.computed().blockers()).isEmpty();
        assertThat(targetResult.stored().inferredSeniority()).isEqualTo("MIDDLE");
        assertThat(targetResult.computed().inferredSeniority()).isEqualTo("JUNIOR");
        assertThat(targetResult.rawComponentTotal()).isEqualTo(55);
        assertThat(targetResult.causedBySeniorityExtractorFix()).isTrue();
        assertThat(targetResult.telegramQueuePositionChanged()).isTrue();
        assertThat(targetResult.oldQueuePosition()).isEqualTo(2);
        assertThat(targetResult.newQueuePosition()).isEqualTo(1);
        assertThat(result.report().storedZeroToPositiveCount()).isEqualTo(1);
        assertThat(result.report().boundaryCrossings().unsuitable()).containsExactly(1L);
        assertThat(result.report().boundaryCrossings().possibleMatch()).containsExactly(1L);
        assertNoRepositoryWrites();
    }

    @Test
    void genuineSeniorEngineerRemainsBlockedAndUnchanged() {
        Job senior = job(7L, "Genuine Senior Engineer", "Build Java software services.",
                ScreeningDisposition.REVIEW, "senior-7");
        ScoreCalculation current = calculator.calculate(senior);
        assertThat(current.score().score()).isZero();
        assertThat(current.score().hardBlockers()).contains("Middle or senior seniority");
        stub(List.of(row(senior, current.score())),
                List.of(requirement(senior, current.requirements())));

        ScoreRescorePreviewResult result = service.preview(250);

        assertThat(result.report().exactMatches()).isEqualTo(1);
        assertThat(result.report().changedJobs()).isEmpty();
        assertThat(result.report().scoreDeltaChangedCount()).isZero();
        assertNoRepositoryWrites();
    }

    /**
     * The write transaction re-persists requirements whenever they differ, so requirements-only
     * drift must enter the plan and be visible in the preview instead of being written unseen.
     */
    @Test
    void requirementsOnlyDifferenceEntersThePlanAndIsVisibleInThePreview() {
        Job target = ordinaryMatch(10L);
        ScoreCalculation current = calculator.calculate(target);
        ExtractedRequirements stored = withSalary(current.requirements(), "legacy salary text");
        stub(List.of(row(target, current.score())), List.of(requirement(target, stored)));

        var result = service.plan(250);

        assertThat(result.status())
                .isEqualTo(ScoreRescorePlanResult.Status.SUCCESS);
        var report = result.plan().report();
        assertThat(report.changeCounts().scoreChangedCount()).isZero();
        assertThat(report.changeCounts().requirementsChangedCount()).isEqualTo(1);
        assertThat(report.changeCounts().requirementsOnlyChangedCount()).isEqualTo(1);
        assertThat(report.changeCounts().changedPlanCount()).isEqualTo(1);
        assertThat(report.changeCounts().unchangedCount()).isZero();
        assertThat(result.plan().changedCount()).isEqualTo(1);
        assertThat(report.changedJobs()).isEmpty();
        assertThat(report.requirementsChangedJobs()).singleElement().satisfies(preview -> {
            assertThat(preview.jobId()).isEqualTo(10L);
            assertThat(preview.scoreChanged()).isFalse();
            assertThat(preview.storedScore()).isEqualTo(preview.computedScore());
            assertThat(preview.changedFields()).singleElement().satisfies(change -> {
                assertThat(change.field())
                        .isEqualTo(ScoreRescorePreviewReport.RequirementField.SALARY);
                assertThat(change.storedValue()).isEqualTo("legacy salary text");
                assertThat(change.computedValue()).isEqualTo("(null)");
            });
        });
        assertNoRepositoryWrites();
    }

    @Test
    void unchangedRowEntersNeitherPlanNorPreviewLists() {
        Job target = ordinaryMatch(11L);
        ScoreCalculation current = calculator.calculate(target);
        stub(List.of(row(target, current.score())),
                List.of(requirement(target, current.requirements())));

        var result = service.plan(250);

        var report = result.plan().report();
        assertThat(report.changeCounts().changedPlanCount()).isZero();
        assertThat(report.changeCounts().unchangedCount()).isEqualTo(1);
        assertThat(report.changedJobs()).isEmpty();
        assertThat(report.requirementsChangedJobs()).isEmpty();
        assertThat(result.plan().entries()).isEmpty();
        assertNoRepositoryWrites();
    }

    /** Every invariant the guarded write relies on, asserted together over one mixed corpus. */
    @Test
    void previewCountsAndPlanStayConsistentAcrossMixedChanges() {
        Job scoreOnly = juniorProgramme(20L);
        ScoreCalculation scoreOnlyCurrent = calculator.calculate(scoreOnly);
        Job requirementsOnly = ordinaryMatch(21L);
        ScoreCalculation requirementsOnlyCurrent = calculator.calculate(requirementsOnly);
        Job unchanged = ordinaryMatch(22L);
        ScoreCalculation unchangedCurrent = calculator.calculate(unchanged);

        stub(List.of(row(scoreOnly, staleScore(scoreOnlyCurrent.score())),
                        row(requirementsOnly, requirementsOnlyCurrent.score()),
                        row(unchanged, unchangedCurrent.score())),
                List.of(requirement(scoreOnly, scoreOnlyCurrent.requirements()),
                        requirement(requirementsOnly,
                                withSalary(requirementsOnlyCurrent.requirements(), "legacy")),
                        requirement(unchanged, unchangedCurrent.requirements())));

        var plan = service.plan(250).plan();
        var counts = plan.report().changeCounts();

        assertThat(counts.changedPlanCount()).isEqualTo(plan.changedCount());
        assertThat(counts.scoreChangedCount() + counts.requirementsOnlyChangedCount())
                .isEqualTo(counts.changedPlanCount());
        assertThat(plan.report().inspectedJobs())
                .isEqualTo(counts.changedPlanCount() + counts.unchangedCount());
        assertThat(plan.report().changedJobs()).hasSize(counts.scoreChangedCount());
        assertThat(plan.report().requirementsChangedJobs())
                .hasSize(counts.requirementsChangedCount());
        assertThat(plan.report().scoreDeltaChangedCount())
                .isLessThanOrEqualTo(counts.scoreChangedCount());

        Set<Long> represented = new java.util.HashSet<>();
        plan.report().changedJobs().forEach(job -> represented.add(job.jobId()));
        plan.report().requirementsChangedJobs().forEach(job -> represented.add(job.jobId()));
        assertThat(represented).containsAll(plan.changedJobIds());
        plan.report().requirementsChangedJobs()
                .forEach(job -> assertThat(job.changedFields()).isNotEmpty());
        assertNoRepositoryWrites();
    }

    @Test
    void missingRequirementsRowFailsClosed() {
        Job target = juniorProgramme(3L);
        ScoreCalculation current = calculator.calculate(target);
        stub(List.of(row(target, current.score())), List.of());

        ScoreRescorePreviewResult result = service.preview(250);

        assertThat(result.status()).isEqualTo(ScoreRescorePreviewResult.Status.ERROR);
        assertThat(result.errorCategory())
                .isEqualTo(ScoreRescorePreviewResult.ErrorCategory.MISSING_REQUIRED_DATA);
        assertThat(result.safeMessage()).contains("job_requirements row", "job 3");
        assertNoRepositoryWrites();
    }

    @Test
    void candidateCapStopsBeforeRowsOrRequirementsAreRead() {
        when(scores.countRescorePreviewCandidates()).thenReturn(251L);

        ScoreRescorePreviewResult result = service.preview(250);

        assertThat(result.status()).isEqualTo(ScoreRescorePreviewResult.Status.ERROR);
        assertThat(result.errorCategory())
                .isEqualTo(ScoreRescorePreviewResult.ErrorCategory.CAP_EXCEEDED);
        verify(scores, never()).findRescorePreviewCandidates(any(Pageable.class));
        verify(requirements, never()).findAllByJobIdIn(any());
        verify(workflow, never()).findAllByJobIdIn(any());
        assertNoRepositoryWrites();
    }

    @Test
    void runningTwiceIsDeterministicAndMakesNoStateChanges() {
        Job target = juniorProgramme(4L);
        ScoreCalculation fresh = calculator.calculate(target);
        ExtractedRequirements stale = withSeniority(fresh.requirements(), "MIDDLE");
        stub(List.of(row(target, calculator.calculate(target, stale).score())),
                List.of(requirement(target, stale)));

        ScoreRescorePreviewResult first = service.preview(250);
        ScoreRescorePreviewResult second = service.preview(250);

        assertThat(second).isEqualTo(first);
        assertNoRepositoryWrites();
    }

    @Test
    void outputIsSanitizedBoundedAndContainsNoDescriptionOrFullExternalId() {
        String longExternalId = "https://example.test/jobs/" + "x".repeat(500);
        Job hostile = job(8L, "Junior Java\nDeveloper\u202E" + "t".repeat(500),
                "PERSONAL-CANDIDATE-DATA build Java software.",
                ScreeningDisposition.MATCH, longExternalId);
        ScoreCalculation fresh = calculator.calculate(hostile);
        ExtractedRequirements stale = withSeniority(fresh.requirements(), "MIDDLE");
        stub(List.of(row(hostile, calculator.calculate(hostile, stale).score())),
                List.of(requirement(hostile, stale)));

        var result = service.preview(250);
        List<String> lines = new ScoreRescorePreviewReportRenderer().render(result);

        assertThat(lines).allSatisfy(line -> {
            assertThat(line).doesNotContain("\n", "\r", "\u202E");
            assertThat(line.length()).isLessThanOrEqualTo(
                    ScoreRescorePreviewReportRenderer.MAX_LINE_LENGTH);
        });
        assertThat(String.join("\n", lines))
                .doesNotContain("PERSONAL-CANDIDATE-DATA", longExternalId)
                .contains("...#");
        assertNoRepositoryWrites();
    }

    @Test
    void repositorySnapshotRejectIsFailedClosedWithoutCalculation() {
        Job rejected = job(9L, "Junior Java Developer", "Build Java software.",
                ScreeningDisposition.REJECT, "rejected-9");
        ScoreCalculation current = calculator.calculate(rejected);
        stub(List.of(row(rejected, current.score())),
                List.of(requirement(rejected, current.requirements())));

        ScoreRescorePreviewResult result = service.preview(250);

        assertThat(result.status()).isEqualTo(ScoreRescorePreviewResult.Status.ERROR);
        assertThat(result.errorCategory())
                .isEqualTo(ScoreRescorePreviewResult.ErrorCategory.INCONSISTENT_PERSISTED_DATA);
        assertThat(result.safeMessage()).contains("REJECT", "job 9");
        assertNoRepositoryWrites();
    }

    private void stub(List<JobScore> scoreRows, List<JobRequirement> requirementRows) {
        when(scores.countRescorePreviewCandidates()).thenReturn((long) scoreRows.size());
        when(scores.findRescorePreviewCandidates(any(Pageable.class))).thenReturn(scoreRows);
        when(requirements.findAllByJobIdIn(any())).thenReturn(requirementRows);
        when(workflow.findAllByJobIdIn(any())).thenReturn(List.of());
    }

    private Job juniorProgramme(long id) {
        return job(id, "Code First Girls Programme - Junior Java Developer",
                "Build software services with Java, Spring Boot and SQL. Graduate training is "
                        + "provided. The Code First Girls mid-level accelerator programme is "
                        + "designed for women with technical experience.",
                ScreeningDisposition.MATCH, "cfg-junior-java");
    }

    private Job ordinaryMatch(long id) {
        return job(id, "Junior Software Engineer", "Build Java software services.",
                ScreeningDisposition.MATCH, "ordinary-" + id);
    }

    private Job job(long id, String title, String description,
                    ScreeningDisposition disposition, String externalId) {
        LocationEligibilityDecision location = new LocationEligibilityDecision(
                WorkplaceType.HYBRID, LocationEligibility.BUCHAREST_LOCAL,
                RemoteScope.ROMANIA, "Bucharest", "Romania", true,
                "Bucharest role", List.of(), null, null);
        EarlyCareerDecision career = new EarlyCareerDecision(SeniorityLevel.JUNIOR,
                ExperienceRequirement.unknown(), EarlyCareerEligibility.ELIGIBLE,
                "Early-career role");
        ScreeningDecision screening = disposition == ScreeningDisposition.MATCH
                ? ScreeningDecision.legacyMatch()
                : new ScreeningDecision(disposition, ScreeningDisposition.MATCH,
                disposition, ScreeningDisposition.MATCH, List.of());
        Job job = new Job("fixture", "tenant", externalId,
                "https://example.test/jobs/" + id, title, "Example", "Bucharest, Romania",
                RemoteType.HYBRID, "Full-time", description,
                Instant.parse("2026-06-01T00:00:00Z"), null, "raw", "description",
                "fingerprint-" + id, Instant.parse("2026-06-01T00:00:00Z"),
                location, career, screening);
        ReflectionTestUtils.setField(job, "id", id);
        job.changeStatus(JobStatus.NEW);
        return job;
    }

    private JobScore row(Job job, ScoreCard score) {
        JobScore row = new JobScore(job, score, NOW);
        ReflectionTestUtils.setField(row, "id", 1_000L + job.getId());
        return row;
    }

    private JobRequirement requirement(Job job, ExtractedRequirements value) {
        JobRequirement row = new JobRequirement(job, value, String.join("|", value.technologies()),
                String.join("|", value.programmingLanguages()),
                String.join("|", value.spokenLanguages()),
                String.join("|", value.mentorshipSignals()), "{}");
        ReflectionTestUtils.setField(row, "id", 2_000L + job.getId());
        return row;
    }

    private ExtractedRequirements withSeniority(ExtractedRequirements value, String seniority) {
        return new ExtractedRequirements(seniority, value.internshipOrTrainee(),
                value.requiredExperienceYears(), value.requiredEducation(),
                value.finalYearMandatory(), value.technologies(), value.programmingLanguages(),
                value.spokenLanguages(), value.location(), value.remoteEligibility(),
                value.mentorshipSignals(), value.workAuthorization(), value.salary(),
                value.applicationDeadline(), value.extractionMethod());
    }

    /** A stored card that differs from the freshly computed one by score alone. */
    private ScoreCard staleScore(ScoreCard fresh) {
        return new ScoreCard(fresh.score() - 5, fresh.band(), fresh.suitable(),
                fresh.formalEligibility(), fresh.javaBackend(), fresh.traineeQuality(),
                fresh.supportingTechnology(), fresh.locationFormat(),
                fresh.experienceCompatibility(), fresh.freshness(), fresh.penalties(),
                fresh.strengths(), fresh.risks(), fresh.hardBlockers());
    }

    private ExtractedRequirements withSalary(ExtractedRequirements value, String salary) {
        return new ExtractedRequirements(value.seniority(), value.internshipOrTrainee(),
                value.requiredExperienceYears(), value.requiredEducation(),
                value.finalYearMandatory(), value.technologies(), value.programmingLanguages(),
                value.spokenLanguages(), value.location(), value.remoteEligibility(),
                value.mentorshipSignals(), value.workAuthorization(), salary,
                value.applicationDeadline(), value.extractionMethod());
    }

    private void assertNoRepositoryWrites() {
        assertNoWrites(scores);
        assertNoWrites(requirements);
        assertNoWrites(workflow);
    }

    private void assertNoWrites(Object repository) {
        assertThat(org.mockito.Mockito.mockingDetails(repository).getInvocations())
                .extracting(Invocation::getMethod)
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(WRITE_METHODS::contains);
    }
}
