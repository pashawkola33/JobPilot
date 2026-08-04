package com.jobpilot.matching.rescore;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCard;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoreRescorePlanFingerprintTest {
    @Test
    void fingerprintIsDeterministicAcrossInputOrderAndChangesWithRequiredState() {
        ScoreRescorePlanEntry first = entry(2, "MIDDLE", "JUNIOR", 0, 56);
        ScoreRescorePlanEntry second = entry(5, "UNKNOWN", "MIDDLE", 38, 0);

        String ordered = ScoreRescorePlanFingerprint.fingerprint(List.of(first, second));
        String reversed = ScoreRescorePlanFingerprint.fingerprint(List.of(second, first));
        ScoreRescorePlanEntry changedContent = new ScoreRescorePlanEntry(second.jobId(),
                second.scoreRowId(), second.requirementRowId(), second.screeningDisposition(),
                second.descriptionHash(), "different-content", second.storedScoredAt(),
                second.storedRequirementJsonHash(), second.storedScore(),
                second.storedRequirements(), second.computedScore(),
                second.computedRequirements());

        assertThat(ordered).matches("[0-9a-f]{64}").isEqualTo(reversed);
        assertThat(ScoreRescorePlanFingerprint.fingerprint(List.of(first, changedContent)))
                .isNotEqualTo(ordered);
    }

    private ScoreRescorePlanEntry entry(long id, String oldSeniority, String newSeniority,
                                         int oldValue, int newValue) {
        return new ScoreRescorePlanEntry(id, 100 + id, 200 + id,
                ScreeningDisposition.REVIEW, "description-" + id, "content-" + id,
                Instant.EPOCH.plusSeconds(id), "json-" + id,
                score(oldValue), requirements(oldSeniority),
                score(newValue), requirements(newSeniority));
    }

    private ScoreCard score(int value) {
        boolean suitable = value > 0;
        return new ScoreCard(value, suitable ? ScoreBand.LOW_MATCH : ScoreBand.UNSUITABLE,
                suitable, 20, 5, 0, 0, 8, 10, 1, 0, List.of(), List.of(),
                suitable ? List.of() : List.of("Middle or senior seniority"));
    }

    private ExtractedRequirements requirements(String seniority) {
        return new ExtractedRequirements(seniority, false, null, null, false,
                List.of("Java"), List.of("Java"), List.of(), "Bucharest", null,
                List.of(), null, null, null, "DETERMINISTIC");
    }
}
