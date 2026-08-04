package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.EarlyCareerEligibility;
import com.jobpilot.jobs.domain.LocationEligibility;
import com.jobpilot.jobs.domain.ScreeningStage;
import com.jobpilot.jobs.domain.SeniorityLevel;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:jobpilot-processor;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@Transactional
class JobProcessorTest {
    @Autowired
    private JobProcessor processor;
    @Autowired
    private JobRepository jobs;
    @Autowired
    private JobRequirementRepository requirements;
    @Autowired
    private JobScoreRepository scores;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void rescoresAnExistingJobWhenItsDescriptionChanges() {
        var first = processor.process(raw("Requires 1 year of experience with Java."));
        assertThat(first.newlyCreated()).isTrue();

        var updated = processor.process(raw("Java internship with structured mentorship in Bucharest, "
                + "Romania. No experience required. Java, Spring Boot, SQL, PostgreSQL and JUnit."));

        assertThat(updated.newlyCreated()).isFalse();
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(updated.job().getId()).isEqualTo(first.job().getId());
        assertThat(updated.score().score()).isNotEqualTo(first.score().score());
        assertThat(scores.findByJobId(first.job().getId()).orElseThrow().getScore())
                .isEqualTo(updated.score().score());
        assertThat(requirements.findByJobId(first.job().getId()).orElseThrow()
                .toValue().internshipOrTrainee()).isTrue();
        assertThat(jobs.findById(first.job().getId()).orElseThrow().getDescription())
                .contains("internship");
        assertThat(jobs.findById(first.job().getId()).orElseThrow().getEarlyCareerEligibility())
                .isEqualTo(EarlyCareerEligibility.ELIGIBLE);
        assertThat(updated.job().getScreeningDisposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(updated.job().getScreeningReasons())
                .extracting(reason -> reason.code())
                .contains("SOFTWARE_DEVELOPMENT_ROLE");
    }

    /**
     * Pins the healing behaviour relied on by Phase 4B.3B: an unchanged vacancy reuses its
     * stored score row rather than recomputing it, so a scoring or extraction fix does NOT
     * retroactively heal already-persisted jobs. Content must actually change first.
     */
    @Test
    void unchangedVacancyReusesItsStoredScoreRowRatherThanRecomputingIt() {
        var first = processor.process(rawWithIdentity("heal-1", "Java Developer Intern",
                "Bucharest, Romania", "Java internship in Bucharest with mentorship."));
        Long scoreRowId = scoreRowId(first.job().getId());

        var again = processor.process(rawWithIdentity("heal-1", "Java Developer Intern",
                "Bucharest, Romania", "Java internship in Bucharest with mentorship."));

        assertThat(again.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UNCHANGED);
        // Same physical row: extractScoreAndSave deletes and reinserts, so a recompute would
        // have produced a new identity.
        assertThat(scoreRowId(again.job().getId())).isEqualTo(scoreRowId);
    }

    /** Changed content does rebuild the score row, which is the path a re-ingest must take. */
    @Test
    void changedVacancyContentRebuildsTheScoreRow() {
        var first = processor.process(rawWithIdentity("heal-2", "Java Developer Intern",
                "Bucharest, Romania", "Java internship in Bucharest with mentorship."));
        Long scoreRowId = scoreRowId(first.job().getId());

        var updated = processor.process(rawWithIdentity("heal-2", "Java Developer Intern",
                "Bucharest, Romania",
                "Java internship in Bucharest with mentorship and Spring Boot, REST and SQL."));

        assertThat(updated.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(scoreRowId(updated.job().getId())).isNotEqualTo(scoreRowId);
    }

    @Test
    void unchangedJobIsRecordedAsSeenWithoutANewRowOrScore() {
        var first = processor.process(raw("Java internship in Bucharest with mentorship."));
        var again = processor.process(raw("Java internship in Bucharest with mentorship."));

        assertThat(again.newlyCreated()).isFalse();
        assertThat(again.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UNCHANGED);
        assertThat(again.job().getId()).isEqualTo(first.job().getId());
        assertThat(again.score().score()).isEqualTo(first.score().score());
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(scores.count()).isEqualTo(1);
        assertThat(requirements.count()).isEqualTo(1);
    }

    @Test
    void sameDescriptionScreeningChangeIsPersistedAndRescoredAsUpdated() {
        String description = "Java internship with mentorship. No experience required.";
        RawJob firstRaw = new RawJob("greenhouse", "screening-refresh",
                "https://example.com/jobs/screening-refresh", "Java Developer Intern", "Example",
                "Remote", description, null, null, null, "first");
        RawJob changedLocation = new RawJob("greenhouse", "screening-refresh",
                "https://example.com/jobs/screening-refresh", "Java Developer Intern", "Example",
                "Bucharest, Romania", description, null, null, null, "second");
        var first = processor.process(firstRaw);

        var updated = processor.process(changedLocation);
        jobs.flush();

        assertThat(first.finalDisposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(updated.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(updated.finalDisposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(updated.score().score()).isNotEqualTo(first.score().score());
        assertThat(scores.findByJobId(first.job().getId()).orElseThrow().getScore())
                .isEqualTo(updated.score().score());
        assertThat(jdbc.queryForObject(
                "select screening_disposition from jobs where id = ?", String.class,
                first.job().getId())).isEqualTo("MATCH");
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(requirements.count()).isEqualTo(1);
        assertThat(scores.count()).isEqualTo(1);
    }

    @Test
    void persistsUnknownRemoteForReviewButRejectsOutOfMarketBeforeScoring() {
        var outside = processor.process(new RawJob("greenhouse", "outside",
                "https://example.com/jobs/outside", "Java Developer", "Example",
                "Cluj-Napoca", "Java role", null, null, null, "outside"));
        var unknown = processor.process(new RawJob("greenhouse", "unknown",
                "https://example.com/jobs/unknown", "Java Developer", "Example",
                "Remote", "Java role", null, null, null, "unknown"));

        assertThat(outside.accepted()).isFalse();
        assertThat(outside.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.NOT_PERSISTED);
        assertThat(outside.eligibilityDecision().locationEligibility())
                .isEqualTo(LocationEligibility.REJECTED_LOCATION);
        assertThat(unknown.accepted()).isTrue();
        assertThat(unknown.eligibilityDecision().locationEligibility())
                .isEqualTo(LocationEligibility.REMOTE_ELIGIBILITY_UNKNOWN);
        assertThat(unknown.finalDisposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(unknown.score()).isNotNull();
        assertThat(unknown.job().getScreeningDisposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(requirements.count()).isEqualTo(1);
        assertThat(scores.count()).isEqualTo(1);
    }

    @Test
    void existingReviewBecomesLocationRejectByStableIdentityAndLosesItsScore() {
        RawJob initiallyReview = new RawJob("greenhouse", "location-reconcile",
                "https://example.com/jobs/location-reconcile", "Java Developer Intern", "Example",
                "Remote", "Java internship with mentorship.", null, null, null, "review");
        var first = processor.process(initiallyReview);
        Instant oldLastSeen = Instant.parse("2020-01-01T00:00:00Z");
        jdbc.update("update jobs set last_seen_at = ?, fetched_at = ? where id = ?",
                oldLastSeen, oldLastSeen, first.job().getId());
        RawJob nowRejected = new RawJob("greenhouse", "location-reconcile",
                "https://different.example/jobs/moved", "Java Developer Intern", "Example",
                "USA | Remote", "Java internship with mentorship.", null, null, null, "reject");
        ScreeningDisposition previousCareer = first.job().getCareerDisposition();
        ScreeningDisposition previousRelevance = first.job().getRelevanceDisposition();

        var result = processor.process(nowRejected);
        jobs.flush();
        var stored = jobs.findById(first.job().getId()).orElseThrow();

        assertThat(result.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(stored.getScreeningDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(stored.getLocationDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(stored.getCareerDisposition()).isEqualTo(previousCareer);
        assertThat(stored.getRelevanceDisposition()).isEqualTo(previousRelevance);
        assertThat(stored.getLastSeenAt()).isAfter(oldLastSeen);
        assertThat(stored.getScreeningReasons())
                .anySatisfy(reason -> {
                    assertThat(reason.stage()).isEqualTo(ScreeningStage.LOCATION);
                    assertThat(reason.message()).contains("United States");
                })
                .anySatisfy(reason -> {
                    assertThat(reason.stage()).isEqualTo(ScreeningStage.FINAL);
                    assertThat(reason.message()).contains("United States");
                });
        assertThat(stored.getCanonicalUrl())
                .isEqualTo("https://example.com/jobs/location-reconcile");
        assertThat(scores.findByJobId(stored.getId())).isEmpty();
        assertThat(scores.findByBandOrderByScoreDesc(
                first.score().band(), Pageable.ofSize(10))).isEmpty();
        assertThat(requirements.count()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void existingMatchBecomesCareerRejectAndRetainsSkippedRelevance() {
        RawJob initial = rawWithIdentity("career-reconcile", "Java Developer Intern",
                "Bucharest, Romania", "No experience required. Build Java services.");
        var first = processor.process(initial);
        RawJob senior = rawWithIdentity("career-reconcile", "Senior Java Developer",
                "Bucharest, Romania", "Build Java services.");

        var result = processor.process(senior);
        var stored = jobs.findById(first.job().getId()).orElseThrow();

        assertThat(first.finalDisposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(result.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(stored.getScreeningDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(stored.getCareerDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(stored.getRelevanceDisposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(stored.getScreeningReasons())
                .extracting(reason -> reason.stage())
                .contains(ScreeningStage.CAREER_LEVEL, ScreeningStage.FINAL)
                .doesNotContain(ScreeningStage.ROLE_RELEVANCE);
        assertThat(scores.findByJobId(stored.getId())).isEmpty();
    }

    @Test
    void existingReviewBecomesRelevanceRejectAndAnIdenticalRepeatIsUnchanged() {
        RawJob initial = rawWithIdentity("relevance-reconcile", "Software Engineering Internship",
                "Remote", "General software development using cloud services.");
        var first = processor.process(initial);
        RawJob irrelevant = rawWithIdentity("relevance-reconcile", "Revenue Accountant",
                "Remote", "Own SQL reporting, APIs, and backend accounting systems.");

        var updated = processor.process(irrelevant);
        var unchanged = processor.process(irrelevant);
        var stored = jobs.findById(first.job().getId()).orElseThrow();

        assertThat(first.finalDisposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(updated.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(unchanged.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UNCHANGED);
        assertThat(stored.getScreeningDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(stored.getRelevanceDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(stored.getScreeningReasons())
                .extracting(reason -> reason.code())
                .contains("NON_ENGINEERING_PRIMARY_FUNCTION", "FINAL_REJECT");
        assertThat(scores.findByJobId(stored.getId())).isEmpty();
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void persistedReviewBecomingBrandDesignerRejectIsUpdatedWithoutANewRowOrScore() {
        RawJob initial = rawWithIdentity("brand-reconcile", "Software Engineering Internship",
                "Remote", "General software development using cloud services.");
        var first = processor.process(initial);
        ScreeningDisposition previousLocation = first.job().getLocationDisposition();
        RawJob brand = rawWithIdentity("brand-reconcile", "Brand Designer", "Remote",
                "Create visual campaigns using SQL dashboards and internal APIs.");

        var result = processor.process(brand);
        var stored = jobs.findById(first.job().getId()).orElseThrow();

        assertThat(first.finalDisposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(result.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(stored.getScreeningDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(stored.getLocationDisposition()).isEqualTo(previousLocation);
        assertThat(stored.getCareerDisposition())
                .isEqualTo(result.earlyCareerDecision().disposition());
        assertThat(stored.getRelevanceDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(stored.getScreeningReasons()).extracting(reason -> reason.code())
                .contains("NON_ENGINEERING_PRIMARY_FUNCTION", "FINAL_REJECT");
        assertThat(scores.findByJobId(stored.getId())).isEmpty();
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void rejectedJobLaterReturnsToMatchAndIsScoredWithoutCreatingADuplicate() {
        RawJob accepted = rawWithIdentity("recover", "Java Developer Intern",
                "Bucharest, Romania", "No experience required. Build Java services.");
        var created = processor.process(accepted);
        var rejected = processor.process(rawWithIdentity("recover", "Senior Java Developer",
                "Bucharest, Romania", "Build Java services."));

        var recovered = processor.process(accepted);

        assertThat(created.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.CREATED);
        assertThat(rejected.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(recovered.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(recovered.finalDisposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(recovered.score()).isNotNull();
        assertThat(scores.findByJobId(created.job().getId())).isPresent();
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void rejectionFallsBackToCanonicalUrlWhenExternalIdentityIsUnavailable() {
        RawJob accepted = new RawJob("fixture", null,
                "https://example.com/jobs/canonical?utm_source=first", "Java Developer Intern",
                "Example", "Bucharest, Romania", "No experience required. Build Java services.",
                null, null, null, "accepted");
        var created = processor.process(accepted);
        RawJob rejected = new RawJob("fixture", null,
                "https://example.com/jobs/canonical?utm_source=second", "Java Developer Intern",
                "Example", "USA | Remote", "Build Java services.", null, null, null, "rejected");

        var result = processor.process(rejected);

        assertThat(result.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.UPDATED);
        assertThat(result.job().getId()).isEqualTo(created.job().getId());
        assertThat(result.job().getScreeningDisposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(scores.findByJobId(created.job().getId())).isEmpty();
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void stableIdentityDoesNotFallThroughToAnUnrelatedCanonicalUrlRow() {
        RawJob accepted = rawWithIdentity("authoritative-id", "Java Developer Intern",
                "Bucharest, Romania", "No experience required. Build Java services.");
        var created = processor.process(accepted);
        RawJob differentIdentity = new RawJob("greenhouse", "different-id", accepted.url(),
                "Java Developer Intern", "Example", "USA | Remote", "Build Java services.",
                null, null, null, "rejected");

        var result = processor.process(differentIdentity);
        var stored = jobs.findById(created.job().getId()).orElseThrow();

        assertThat(result.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.NOT_PERSISTED);
        assertThat(stored.getScreeningDisposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(scores.findByJobId(stored.getId())).isPresent();
        assertThat(jobs.count()).isEqualTo(1);
    }

    @Test
    void normalizedBucharestSpellingsDeduplicateBeforeScoringAgain() {
        var first = processor.process(new RawJob("greenhouse", "variant-1",
                "https://example.com/jobs/variant-1", "Java Developer Intern", "Example",
                "Bucharest, Romania", "Java internship with mentorship.", null,
                null, null, "one"));
        var spellingVariant = processor.process(new RawJob("lever", "variant-2",
                "https://example.com/jobs/variant-2", "Java Developer Intern", "Example",
                "București, România", "Java internship with mentorship.", null,
                null, null, "two"));

        assertThat(first.newlyCreated()).isTrue();
        assertThat(spellingVariant.newlyCreated()).isFalse();
        assertThat(spellingVariant.job().getId()).isEqualTo(first.job().getId());
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(scores.count()).isEqualTo(1);
    }

    @Test
    void persistsUnknownCareerForReviewButRejectsSeniorRoles() {
        var unknown = processor.process(new RawJob("greenhouse", "career-unknown",
                "https://example.com/jobs/career-unknown", "Software Engineer", "Example",
                "Bucharest", "Build Java services.", null, null, null, "unknown-career"));
        var senior = processor.process(new RawJob("greenhouse", "senior",
                "https://example.com/jobs/senior", "Senior Software Engineer", "Example",
                "Bucharest", "Build Java services.", null, null, null, "senior"));

        assertThat(unknown.accepted()).isTrue();
        assertThat(unknown.earlyCareerDecision().earlyCareerEligibility())
                .isEqualTo(EarlyCareerEligibility.UNKNOWN);
        assertThat(unknown.finalDisposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(senior.accepted()).isFalse();
        assertThat(senior.earlyCareerDecision().seniorityLevel()).isEqualTo(SeniorityLevel.SENIOR);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(requirements.count()).isEqualTo(1);
        assertThat(scores.count()).isEqualTo(1);
    }

    @Test
    void persistsJuniorTitleWithMandatoryThreeYearsForReview() {
        var result = processor.process(new RawJob("greenhouse", "junior-three",
                "https://example.com/jobs/junior-three", "Junior Java Developer", "Example",
                "Remote — Europe", "Requires 3+ years of professional experience.",
                null, null, null, "junior-three"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.earlyCareerDecision().earlyCareerEligibility())
                .isEqualTo(EarlyCareerEligibility.UNKNOWN);
        assertThat(result.finalDisposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(result.score()).isNotNull();
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(scores.count()).isEqualTo(1);
    }

    @Test
    void digestCannotReturnLegacyUnknownCareerRows() {
        var accepted = processor.process(raw("No previous experience required. Java internship."));
        jobs.flush();

        assertThat(scores.findDigest(accepted.score().band(), Instant.EPOCH, Pageable.ofSize(10)))
                .extracting(score -> score.getJob().getId()).contains(accepted.job().getId());

        jdbc.update("update jobs set screening_disposition = 'REVIEW' where id = ?",
                accepted.job().getId());

        assertThat(scores.findDigest(accepted.score().band(), Instant.EPOCH, Pageable.ofSize(10)))
                .isEmpty();
    }

    @Test
    void rescorePreviewRepositoryQueryNeverReturnsRejectJobsEvenWithALegacyScoreRow() {
        var accepted = processor.process(rawWithIdentity("preview-reject", "Java Developer Intern",
                "Bucharest, Romania", "No experience required. Build Java services."));
        jobs.flush();
        jdbc.update("update jobs set screening_disposition = 'REJECT' where id = ?",
                accepted.job().getId());

        assertThat(scores.countRescorePreviewCandidates()).isZero();
        assertThat(scores.findRescorePreviewCandidates(Pageable.ofSize(10))).isEmpty();
    }

    /** Physical job_scores row identity; extractScoreAndSave deletes and reinserts. */
    private Long scoreRowId(Long jobId) {
        return jdbc.queryForObject("select id from job_scores where job_id = ?", Long.class, jobId);
    }

    private RawJob raw(String description) {
        return new RawJob("greenhouse", "42", "https://example.com/jobs/42", "Java Developer Intern",
                "Example", "Bucharest, Romania", description, null,
                Instant.parse("2026-07-16T10:00:00Z"), null, description);
    }

    private RawJob rawWithIdentity(String id, String title, String location, String description) {
        return new RawJob("greenhouse", id, "https://example.com/jobs/" + id, title,
                "Example", location, description, null, null, null, description);
    }
}
