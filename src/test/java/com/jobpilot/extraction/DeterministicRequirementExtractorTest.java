package com.jobpilot.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.service.JobNormalizer;
import com.jobpilot.jobs.domain.RawJob;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DeterministicRequirementExtractorTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);
    private final JobNormalizer normalizer = new JobNormalizer(clock, new UrlCanonicalizer());
    private final DeterministicRequirementExtractor extractor = new DeterministicRequirementExtractor();

    @Test
    void extractsSkillsEligibilityAndMentorshipSignals() throws Exception {
        var requirements = extractor.extract(job("Java Developer Intern", "strong-java-internship.txt"));

        assertThat(requirements.internshipOrTrainee()).isTrue();
        assertThat(requirements.finalYearMandatory()).isFalse();
        assertThat(requirements.technologies()).contains("Java", "Spring Boot", "REST", "PostgreSQL",
                "JPA", "Maven", "JUnit", "React", "TypeScript", "CI/CD");
        assertThat(requirements.mentorshipSignals()).contains("mentor", "structured mentorship");
        assertThat(requirements.extractionMethod()).isEqualTo("DETERMINISTIC");
    }

    @Test
    void extractsFinalYearExperienceAndLanguages() throws Exception {
        var finalYear = extractor.extract(job("Java Internship", "final-year-only.txt"));
        var senior = extractor.extract(job("Senior Java Developer", "senior-java.txt"));
        var french = extractor.extract(job("Java Intern", "mandatory-french.txt"));

        assertThat(finalYear.finalYearMandatory()).isTrue();
        assertThat(senior.requiredExperienceYears()).isEqualTo(5);
        assertThat(senior.seniority()).isEqualTo("SENIOR");
        assertThat(french.spokenLanguages()).anyMatch(value -> value.startsWith("French"));
    }

    @Test
    void doesNotTreatInternalOrInternationalWordsAsInternship() {
        var requirements = extractor.extract(jobWithDescription("Java Developer",
                "Work on internal tools for our international clients. "
                        + "You will collaborate internally with product teams."));

        assertThat(requirements.internshipOrTrainee()).isFalse();
        assertThat(requirements.seniority()).isNotEqualTo("INTERNSHIP");
    }

    @Test
    void keepsRealInternshipAndTraineeSignals() {
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "We welcome interns to our team.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "This internship lasts six months.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "Join as a trainee developer.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "A twelve-month apprenticeship with mentoring.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "Our Java academy starts in October.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "Apply to our graduate program in Bucharest.")).internshipOrTrainee()).isTrue();
        assertThat(extractor.extract(jobWithDescription("Java Role",
                "Apply to our graduate programme in Bucharest.")).internshipOrTrainee()).isTrue();
    }

    private Job jobWithDescription(String title, String description) {
        return normalizer.normalize(new RawJob("fixture", "text-1", "https://example.com/jobs/text-1",
                title, "Example", "Bucharest, Romania", description, null,
                clock.instant(), null, description));
    }

    private Job job(String title, String fixture) throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/fixtures/" + fixture)) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return normalizer.normalize(new RawJob("fixture", fixture, "https://example.com/" + fixture,
                title, "Example", "Bucharest, Romania", text, "Internship",
                clock.instant(), null, text));
    }
}
