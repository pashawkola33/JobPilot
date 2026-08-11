package com.jobpilot.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.service.JobNormalizer;
import com.jobpilot.jobs.service.JobRelevanceFilter;
import com.jobpilot.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ScoringSemanticsContractTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    private final DeterministicRequirementExtractor extractor =
            new DeterministicRequirementExtractor();

    @Test
    void rejectedNonEngineeringRoleCannotReachPossibleMatchWithoutBackendEvidence() {
        String description = """
                Graduate training programme for current students with a Computer Science
                or related-field background.

                Work from Bucharest, Romania with engineering and product teams.
                The organisation uses React, TypeScript, JavaScript, HTML, CSS,
                Git, CI/CD and GitHub Actions.
                """;

        RawJob raw = new RawJob(
                "contract",
                "product-marketing-graduate",
                "https://example.com/jobs/product-marketing-graduate",
                "Product Marketing Graduate",
                "Example",
                "Bucharest, Romania",
                description,
                "Full-time",
                NOW,
                null,
                description);

        var properties = TestProperties.create();
        var relevance = new JobRelevanceFilter(properties).evaluate(raw);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Job job = new JobNormalizer(clock, new UrlCanonicalizer()).normalize(raw);
        ScoreCard score = new JobMatchingService(clock, properties)
                .score(job, extractor.extract(job));

        assertThat(relevance.disposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(score.javaBackend()).isZero();
        assertThat(score.score()).isLessThan(55);
        assertThat(score.band()).isEqualTo(ScoreBand.LOW_MATCH);
    }

    @Test
    void zeroBackendEvidenceCannotReachPossibleMatch() {
        ScoreCard score = score(
                Clock.fixed(NOW, ZoneOffset.UTC),
                "Implementation Engineer",
                """
                Current students and graduates with a Computer Science background are welcome.
                Work in Bucharest, Romania.

                Tools include React, TypeScript, JavaScript, HTML, CSS,
                Git, CI/CD and GitHub Actions.
                """,
                NOW);

        assertThat(score.javaBackend()).isZero();

        // A Java/backend candidate should not receive semantic match status
        // from generic eligibility and supporting tooling alone.
        assertThat(score.score()).isLessThan(55);
        assertThat(score.band()).isEqualTo(ScoreBand.LOW_MATCH);
    }

    @Test
    void targetBackendEvidenceMustOutrankSupportingOnlyProfile() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        ScoreCard supportingOnly = score(
                clock,
                "Implementation Engineer",
                """
                Current students and graduates with a Computer Science background are welcome.
                Work in Bucharest, Romania.
                Tools include React, TypeScript, JavaScript, HTML, CSS,
                Git, CI/CD and GitHub Actions.
                """,
                NOW);

        ScoreCard javaBackend = score(
                clock,
                "Java Backend Engineer",
                """
                Current students and graduates with a Computer Science background are welcome.
                Build Java services with Spring Boot, REST APIs, SQL and PostgreSQL.
                Work in Bucharest, Romania.
                """,
                NOW);

        assertThat(javaBackend.javaBackend())
                .isGreaterThan(supportingOnly.javaBackend());

        assertThat(javaBackend.score())
                .isGreaterThan(supportingOnly.score());
    }

    @Test
    void freshnessChangesComponentButNotSemanticFitScore() {
        Instant published = Instant.parse("2026-07-17T12:00:00Z");

        Clock day14 = Clock.fixed(
                Instant.parse("2026-07-31T12:00:00Z"),
                ZoneOffset.UTC);

        Clock day15 = Clock.fixed(
                Instant.parse("2026-08-01T12:00:00Z"),
                ZoneOffset.UTC);

        String description = """
                Build Java services with Spring Boot.
                Work from Bucharest, Romania.
                """;

        ScoreCard at14 = score(
                day14,
                "Java Backend Engineer",
                description,
                published);

        ScoreCard at15 = score(
                day15,
                "Java Backend Engineer",
                description,
                published);

        assertThat(at14.freshness()).isGreaterThan(at15.freshness());

        // Recency may remain observable for ordering/explanation,
        // but must not mutate the persisted semantic fit score.
        assertThat(at14.score()).isEqualTo(at15.score());
        assertThat(at14.band()).isEqualTo(at15.band());
    }

    private ScoreCard score(
            Clock clock,
            String title,
            String description,
            Instant publishedAt) {

        RawJob raw = new RawJob(
                "contract",
                title.toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                "https://example.com/jobs/"
                        + title.toLowerCase().replaceAll("[^a-z0-9]+", "-"),
                title,
                "Example",
                "Bucharest, Romania",
                description,
                "Full-time",
                publishedAt,
                null,
                description);

        Job job = new JobNormalizer(clock, new UrlCanonicalizer()).normalize(raw);

        return new JobMatchingService(clock, TestProperties.create())
                .score(job, extractor.extract(job));
    }
}
