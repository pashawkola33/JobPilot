package com.jobpilot.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.service.JobNormalizer;
import com.jobpilot.support.TestProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JobMatchingServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC);
    private final JobNormalizer normalizer = new JobNormalizer(clock);
    private final DeterministicRequirementExtractor extractor = new DeterministicRequirementExtractor();
    private final JobMatchingService matching = new JobMatchingService(clock, TestProperties.create());

    @Test
    void scoresStrongJavaInternshipAsExcellentWithReasons() throws Exception {
        ScoreCard score = score("Java Developer Intern", "strong-java-internship.txt", "Bucharest, Romania");
        assertThat(score.score()).isGreaterThanOrEqualTo(85);
        assertThat(score.band()).isEqualTo(ScoreBand.EXCELLENT_MATCH);
        assertThat(score.strengths()).isNotEmpty();
        assertThat(score.hardBlockers()).isEmpty();
    }

    @Test
    void appliesFinalYearAndMandatoryFrenchPenalties() throws Exception {
        ScoreCard finalYear = score("Java Internship", "final-year-only.txt", "Bucharest, Romania");
        ScoreCard french = score("Java Intern", "mandatory-french.txt", "Bucharest, Romania");
        assertThat(finalYear.penalties()).isGreaterThanOrEqualTo(30);
        assertThat(finalYear.risks()).anyMatch(value -> value.contains("Final-year"));
        assertThat(french.penalties()).isGreaterThanOrEqualTo(25);
    }

    @Test
    void hardBlocksSeniorRemoteIneligibleAndClosedVacancies() throws Exception {
        assertBlocked(score("Senior Java Developer", "senior-java.txt", "Bucharest"), "senior");
        assertBlocked(score("Software Engineer Intern", "remote-not-romania.txt", "Remote"), "Romania");
        assertBlocked(score("Java Internship", "closed-vacancy.txt", "Romania"), "closed");
    }

    @Test
    void unclearVacancyRemainsLowWithoutInventingRequirements() throws Exception {
        ScoreCard score = score("Technology Opportunity", "unclear-requirements.txt", "Bucharest");
        assertThat(score.band()).isIn(ScoreBand.LOW_MATCH, ScoreBand.POSSIBLE_MATCH);
        assertThat(score.javaBackend()).isZero();
    }

    private void assertBlocked(ScoreCard card, String fragment) {
        assertThat(card.band()).isEqualTo(ScoreBand.UNSUITABLE);
        assertThat(card.score()).isZero();
        assertThat(card.hardBlockers()).anyMatch(value -> value.toLowerCase().contains(fragment.toLowerCase()));
    }

    private ScoreCard score(String title, String fixture, String location) throws Exception {
        String text;
        try (var stream = getClass().getResourceAsStream("/fixtures/" + fixture)) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Job job = normalizer.normalize(new RawJob("fixture", fixture, "https://example.com/" + fixture,
                title, "Example", location, text, "Internship", clock.instant(), null, text));
        return matching.score(job, extractor.extract(job));
    }
}
