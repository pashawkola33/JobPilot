package com.jobpilot.matching.rescore;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCard;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Plan membership must follow either persisted projection, not the score alone. */
class ScoreRescorePlanEntryChangeTest {

    @Test
    void scoreOnlyChangeIsPlanned() {
        var entry = entry(card(50), card(56), requirements("JUNIOR"), requirements("JUNIOR"));

        assertThat(entry.changed()).isTrue();
        assertThat(entry.scoreChanged()).isTrue();
        assertThat(entry.requirementsChanged()).isFalse();
    }

    @Test
    void requirementsOnlyChangeIsPlanned() {
        var entry = entry(card(56), card(56), requirements("JUNIOR"), requirements("UNKNOWN"));

        assertThat(entry.changed()).isTrue();
        assertThat(entry.scoreChanged()).isFalse();
        assertThat(entry.requirementsChanged()).isTrue();
    }

    @Test
    void bothChangedIsPlanned() {
        var entry = entry(card(50), card(56), requirements("JUNIOR"), requirements("UNKNOWN"));

        assertThat(entry.changed()).isTrue();
        assertThat(entry.scoreChanged()).isTrue();
        assertThat(entry.requirementsChanged()).isTrue();
    }

    @Test
    void unchangedRowIsNotPlanned() {
        var entry = entry(card(56), card(56), requirements("JUNIOR"), requirements("JUNIOR"));

        assertThat(entry.changed()).isFalse();
        assertThat(entry.scoreChanged()).isFalse();
        assertThat(entry.requirementsChanged()).isFalse();
    }

    private ScoreRescorePlanEntry entry(ScoreCard storedScore, ScoreCard computedScore,
                                        ExtractedRequirements storedRequirements,
                                        ExtractedRequirements computedRequirements) {
        return new ScoreRescorePlanEntry(5, 10, 20, ScreeningDisposition.REVIEW,
                "description", "content", Instant.EPOCH, "json",
                storedScore, storedRequirements, computedScore, computedRequirements);
    }

    private ScoreCard card(int score) {
        return new ScoreCard(score, ScoreBand.POSSIBLE_MATCH, true, 25, 9, 3, 0, 8, 10, 1, 0,
                List.of(), List.of(), List.of());
    }

    private ExtractedRequirements requirements(String seniority) {
        return new ExtractedRequirements(seniority, false, null, null, false,
                List.of("Java"), List.of("Java"), List.of(), "Bucharest",
                "Romania eligible", List.of(), null, null, null, "DETERMINISTIC");
    }
}
