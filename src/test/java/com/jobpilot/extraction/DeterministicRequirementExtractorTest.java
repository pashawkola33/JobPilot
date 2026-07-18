package com.jobpilot.extraction;

import static org.assertj.core.api.Assertions.assertThat;

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
    private final JobNormalizer normalizer = new JobNormalizer(clock);
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
