package com.jobpilot.matching.rescore;

import com.jobpilot.matching.preview.ScoreRescorePreviewReport;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable preview report plus the complete changed-row write plan. */
public record ScoreRescorePlan(
        ScoreRescorePreviewReport report,
        List<ScoreRescorePlanEntry> entries,
        String fingerprint) {

    public ScoreRescorePlan(ScoreRescorePreviewReport report,
                           List<ScoreRescorePlanEntry> entries) {
        this(report, normalized(entries), ScoreRescorePlanFingerprint.fingerprint(entries));
    }

    public ScoreRescorePlan {
        Objects.requireNonNull(report, "Preview report is required");
        entries = normalized(entries);
        // The write may only touch rows the preview accounted for, so the two are tied here
        // rather than left to agree by convention.
        if (report.changeCounts() == null
                || report.changeCounts().changedPlanCount() != entries.size()) {
            throw new IllegalArgumentException(
                    "Plan entry count does not match the preview report changed plan count");
        }
        if (!ScoreRescorePlanFingerprint.fingerprint(entries).equals(fingerprint)) {
            throw new IllegalArgumentException("Plan fingerprint does not match its entries");
        }
    }

    public int changedCount() {
        return entries.size();
    }

    public List<Long> changedJobIds() {
        return entries.stream().map(ScoreRescorePlanEntry::jobId).toList();
    }

    private static List<ScoreRescorePlanEntry> normalized(List<ScoreRescorePlanEntry> values) {
        Objects.requireNonNull(values, "Plan entries are required");
        List<ScoreRescorePlanEntry> ordered = values.stream()
                .peek(value -> {
                    if (!value.changed()) {
                        throw new IllegalArgumentException("Plan contains an unchanged entry");
                    }
                })
                .sorted(Comparator.comparingLong(ScoreRescorePlanEntry::jobId)).toList();
        if (new HashSet<>(ordered.stream().map(ScoreRescorePlanEntry::jobId).toList()).size()
                != ordered.size()) {
            throw new IllegalArgumentException("Plan contains duplicate job identifiers");
        }
        return List.copyOf(ordered);
    }
}
