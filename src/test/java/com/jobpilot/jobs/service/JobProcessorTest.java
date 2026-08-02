package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.EarlyCareerEligibility;
import com.jobpilot.jobs.domain.LocationEligibility;
import com.jobpilot.jobs.domain.SeniorityLevel;
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
    }

    @Test
    void unchangedJobIsRecordedAsSeenWithoutANewRowOrScore() {
        var first = processor.process(raw("Java internship in Bucharest with mentorship."));
        var again = processor.process(raw("Java internship in Bucharest with mentorship."));

        assertThat(again.newlyCreated()).isFalse();
        assertThat(again.job().getId()).isEqualTo(first.job().getId());
        assertThat(again.score().score()).isEqualTo(first.score().score());
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(scores.count()).isEqualTo(1);
        assertThat(requirements.count()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownAndOutOfMarketJobsBeforePersistenceOrScoring() {
        var outside = processor.process(new RawJob("greenhouse", "outside",
                "https://example.com/jobs/outside", "Java Developer", "Example",
                "Cluj-Napoca", "Java role", null, null, null, "outside"));
        var unknown = processor.process(new RawJob("greenhouse", "unknown",
                "https://example.com/jobs/unknown", "Java Developer", "Example",
                "Remote", "Java role", null, null, null, "unknown"));

        assertThat(outside.accepted()).isFalse();
        assertThat(outside.eligibilityDecision().locationEligibility())
                .isEqualTo(LocationEligibility.REJECTED_LOCATION);
        assertThat(unknown.accepted()).isFalse();
        assertThat(unknown.eligibilityDecision().locationEligibility())
                .isEqualTo(LocationEligibility.REMOTE_ELIGIBILITY_UNKNOWN);
        assertThat(jobs.count()).isZero();
        assertThat(requirements.count()).isZero();
        assertThat(scores.count()).isZero();
    }

    @Test
    void geographicRejectionDoesNotMutateAnExistingJob() {
        var accepted = processor.process(raw("Java internship in Bucharest with mentorship."));
        var rejectedRefresh = processor.process(new RawJob("greenhouse", "42",
                "https://example.com/jobs/42", "Java Developer", "Example", "Remote",
                "Fully remote Java role. United States only.", null,
                Instant.parse("2026-07-16T10:00:00Z"), null, "rejected-refresh"));

        assertThat(rejectedRefresh.accepted()).isFalse();
        assertThat(jobs.findById(accepted.job().getId()).orElseThrow().getDescription())
                .contains("Bucharest").doesNotContain("United States only");
        assertThat(scores.count()).isEqualTo(1);
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
    void rejectsUnknownAndSeniorRolesBeforePersistenceOrScoring() {
        var unknown = processor.process(new RawJob("greenhouse", "career-unknown",
                "https://example.com/jobs/career-unknown", "Software Engineer", "Example",
                "Bucharest", "Build Java services.", null, null, null, "unknown-career"));
        var senior = processor.process(new RawJob("greenhouse", "senior",
                "https://example.com/jobs/senior", "Senior Software Engineer", "Example",
                "Bucharest", "Build Java services.", null, null, null, "senior"));

        assertThat(unknown.accepted()).isFalse();
        assertThat(unknown.earlyCareerDecision().earlyCareerEligibility())
                .isEqualTo(EarlyCareerEligibility.UNKNOWN);
        assertThat(senior.accepted()).isFalse();
        assertThat(senior.earlyCareerDecision().seniorityLevel()).isEqualTo(SeniorityLevel.SENIOR);
        assertThat(jobs.count()).isZero();
        assertThat(requirements.count()).isZero();
        assertThat(scores.count()).isZero();
    }

    @Test
    void rejectsJuniorTitleWithMandatoryThreeYears() {
        var result = processor.process(new RawJob("greenhouse", "junior-three",
                "https://example.com/jobs/junior-three", "Junior Java Developer", "Example",
                "Remote — Europe", "Requires 3+ years of professional experience.",
                null, null, null, "junior-three"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.earlyCareerDecision().earlyCareerEligibility())
                .isEqualTo(EarlyCareerEligibility.INELIGIBLE);
        assertThat(jobs.count()).isZero();
        assertThat(scores.count()).isZero();
    }

    @Test
    void digestCannotReturnLegacyUnknownCareerRows() {
        var accepted = processor.process(raw("No previous experience required. Java internship."));
        jobs.flush();

        assertThat(scores.findDigest(accepted.score().band(), Instant.EPOCH, Pageable.ofSize(10)))
                .extracting(score -> score.getJob().getId()).contains(accepted.job().getId());

        jdbc.update("update jobs set early_career_eligibility = 'UNKNOWN' where id = ?",
                accepted.job().getId());

        assertThat(scores.findDigest(accepted.score().band(), Instant.EPOCH, Pageable.ofSize(10)))
                .isEmpty();
    }

    private RawJob raw(String description) {
        return new RawJob("greenhouse", "42", "https://example.com/jobs/42", "Java Developer Intern",
                "Example", "Bucharest, Romania", description, null,
                Instant.parse("2026-07-16T10:00:00Z"), null, description);
    }
}
