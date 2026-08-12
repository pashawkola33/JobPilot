package com.jobpilot.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.service.JobNormalizer;
import com.jobpilot.support.TestProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CandidateMatchingProfileTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);
    private final JobNormalizer normalizer = new JobNormalizer(clock, new UrlCanonicalizer());
    private final DeterministicRequirementExtractor extractor = new DeterministicRequirementExtractor();
    private final JobMatchingService matching = new JobMatchingService(clock, TestProperties.create());

    @Test
    void legacyDefaultAndEquivalentExplicitProfileScoreIdentically() throws Exception {
        Job job = job("Java Developer Intern", "strong-java-internship.txt", "Bucharest, Romania");
        ExtractedRequirements requirements = extractor.extract(job);

        CandidateMatchingProfile explicit = new CandidateMatchingProfile("Romania",
                Set.of("Java", "Spring Boot", "REST", "SQL", "PostgreSQL", "JPA", "Maven", "JUnit"),
                Set.of("React", "TypeScript", "JavaScript", "HTML", "CSS", "Git", "CI/CD",
                        "GitHub Actions"),
                true, false, 0);

        assertThat(matching.score(job, requirements, explicit))
                .isEqualTo(matching.score(job, requirements));
    }

    @Test
    void sameVacancyScoresDifferentlyForDifferentCandidates() throws Exception {
        Job job = job("Java Developer Intern", "strong-java-internship.txt", "Bucharest, Romania");
        ExtractedRequirements requirements = extractor.extract(job);

        CandidateMatchingProfile javaCandidate = new CandidateMatchingProfile("Romania",
                Set.of("Java", "Spring Boot", "REST", "SQL", "PostgreSQL"),
                Set.of("Git", "CI/CD"), true, false, 0);
        CandidateMatchingProfile dataCandidate = new CandidateMatchingProfile("Romania",
                Set.of("Python", "Django", "Pandas", "NumPy", "Airflow"),
                Set.of("Git", "CI/CD"), true, false, 0);

        ScoreCard javaScore = matching.score(job, requirements, javaCandidate);
        ScoreCard dataScore = matching.score(job, requirements, dataCandidate);

        assertThat(javaScore.javaBackend()).isPositive();
        assertThat(dataScore.javaBackend()).isZero();
        assertThat(javaScore.score()).isGreaterThan(dataScore.score());
        assertThat(dataScore.risks()).anyMatch(value -> value.contains("Java/backend"));
    }

    @Test
    void convertsLegacyCandidateConfigurationWithoutLosingValues() {
        JobPilotProperties.Candidate legacy = TestProperties.create().candidate();

        CandidateMatchingProfile profile = CandidateMatchingProfile.fromLegacy(legacy);

        assertThat(profile.homeCountry()).isEqualTo("Romania");
        assertThat(profile.backendSkills()).containsExactlyInAnyOrder("java", "spring boot",
                "rest", "sql", "postgresql", "jpa", "maven", "junit");
        assertThat(profile.supportingSkills()).contains("react", "github actions");
        assertThat(profile.currentStudent()).isTrue();
        assertThat(profile.finalYearStudent()).isFalse();
        assertThat(profile.commercialJavaYears()).isZero();
    }

    @Test
    void copiesSkillCollectionsDefensively() {
        Set<String> backendSkills = new LinkedHashSet<>(Set.of("Java"));

        CandidateMatchingProfile profile = new CandidateMatchingProfile("Romania",
                backendSkills, Set.of("Git"), true, false, 0);
        backendSkills.add("Python");

        assertThat(profile.backendSkills()).containsExactly("java");
        assertThatThrownBy(() -> profile.backendSkills().add("python"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsMissingCandidateValues() {
        assertThatThrownBy(() -> new CandidateMatchingProfile(null, Set.of("Java"), Set.of(), true, false, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidateMatchingProfile("Romania", null, Set.of(), true, false, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CandidateMatchingProfile("Romania", Set.of("Java"), null, true, false, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CandidateMatchingProfile("Romania", Set.of("Java"), Set.of(), true, false, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CandidateMatchingProfile.fromLegacy(null))
                .isInstanceOf(NullPointerException.class);
    }

    private Job job(String title, String fixture, String location) throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/fixtures/" + fixture)) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        return normalizer.normalize(new RawJob("fixture", fixture, "https://example.com/" + fixture,
                title, "Example", location, text, "Internship", clock.instant(), null, text));
    }
}
