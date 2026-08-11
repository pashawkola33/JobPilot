package com.jobpilot.matching.rescore;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCalculation;
import com.jobpilot.matching.ScoreCard;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreRescoreFreshnessSemanticsTest {

    @Test
    void freshnessOnlyDifferenceDoesNotCreateRescorePlanEntry() {
        ScoreCard stored = card(55, 5);
        ScoreCard computed = card(55, 4);
        ExtractedRequirements requirements = requirements();

        var entry = new ScoreRescorePlanEntry(
                5,
                10,
                20,
                ScreeningDisposition.REVIEW,
                "description-hash",
                "content-hash",
                Instant.EPOCH,
                "requirements-hash",
                stored,
                requirements,
                computed,
                requirements);

        assertThat(entry.scoreChanged()).isFalse();
        assertThat(entry.requirementsChanged()).isFalse();
        assertThat(entry.changed()).isFalse();
    }

    @Test
    void rawComponentTotalExcludesNonSemanticFreshness() {
        ScoreCard score = card(55, 5);
        ScoreCalculation calculation =
                new ScoreCalculation(requirements(), score);

        // 25 formal
        // + 9 backend
        // + 3 trainee
        // + 0 supporting
        // + 8 location
        // + 10 experience
        // - 0 penalties
        // = 55
        //
        // Freshness remains observable as 5 but is not semantic fit.
        assertThat(score.freshness()).isEqualTo(5);
        assertThat(calculation.rawComponentTotal()).isEqualTo(55);
    }


    @Test
    void computedFreshnessDoesNotChangeSemanticPlanFingerprint() {
        ExtractedRequirements requirements = requirements();
        ScoreCard stored = card(50, 5);

        var first = new ScoreRescorePlanEntry(
                5, 10, 20, ScreeningDisposition.REVIEW,
                "description-hash", "content-hash", Instant.EPOCH, "requirements-hash",
                stored, requirements, card(55, 5), requirements);

        var second = new ScoreRescorePlanEntry(
                5, 10, 20, ScreeningDisposition.REVIEW,
                "description-hash", "content-hash", Instant.EPOCH, "requirements-hash",
                stored, requirements, card(55, 4), requirements);

        assertThat(ScoreRescorePlanFingerprint.fingerprint(List.of(first)))
                .isEqualTo(ScoreRescorePlanFingerprint.fingerprint(List.of(second)));
    }


    @Test
    void writeGuardToleratesComputedFreshnessMetadataDriftButProtectsStoredState() {
        ExtractedRequirements requirements = requirements();
        ScoreCard stored = card(50, 5);

        ScoreCard recent = card(55, 4,
                List.of("Vacancy is recent and appears open"));
        ScoreCard older = card(55, 2, List.of());

        var expected = new ScoreRescorePlanEntry(
                5, 10, 20, ScreeningDisposition.REVIEW,
                "description-hash", "content-hash", Instant.EPOCH, "requirements-hash",
                stored, requirements, recent, requirements);

        var recomputedLater = new ScoreRescorePlanEntry(
                5, 10, 20, ScreeningDisposition.REVIEW,
                "description-hash", "content-hash", Instant.EPOCH, "requirements-hash",
                stored, requirements, older, requirements);

        var persistedStateChanged = new ScoreRescorePlanEntry(
                5, 10, 20, ScreeningDisposition.REVIEW,
                "description-hash", "content-hash", Instant.EPOCH, "requirements-hash",
                card(50, 4), requirements, older, requirements);

        assertThat(recent.semanticEquals(older)).isTrue();
        assertThat(recomputedLater.sameWriteGuardState(expected)).isTrue();
        assertThat(persistedStateChanged.sameWriteGuardState(expected)).isFalse();

        assertThat(ScoreRescorePlanFingerprint.fingerprint(List.of(expected)))
                .isEqualTo(ScoreRescorePlanFingerprint.fingerprint(List.of(recomputedLater)));
    }

    private ScoreCard card(int score, int freshness) {
        return card(score, freshness, List.of());
    }

    private ScoreCard card(int score, int freshness, List<String> strengths) {
        return new ScoreCard(
                score,
                ScoreBand.POSSIBLE_MATCH,
                true,
                25,
                9,
                3,
                0,
                8,
                10,
                freshness,
                0,
                strengths,
                List.of(),
                List.of());
    }

    private ExtractedRequirements requirements() {
        return new ExtractedRequirements(
                "JUNIOR",
                false,
                null,
                null,
                false,
                List.of("Java"),
                List.of("Java"),
                List.of(),
                "Bucharest",
                "Romania eligible",
                List.of(),
                null,
                null,
                null,
                "DETERMINISTIC");
    }
}
