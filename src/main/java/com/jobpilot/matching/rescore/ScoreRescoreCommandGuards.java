package com.jobpilot.matching.rescore;

import com.jobpilot.matching.preview.ScoreRescorePreviewProperties;
import java.util.Locale;

/** Fail-closed parsing and matching of every explicit one-shot guard. */
public final class ScoreRescoreCommandGuards {
    public static final String CONFIRMATION = "APPLY_STALE_SCORE_PLAN_ONCE";

    private ScoreRescoreCommandGuards() {
    }

    public static GuardValidation validateWrite(ScoreRescoreCommandProperties properties,
                                                ScoreRescorePlan plan) {
        if (properties.mode() != ScoreRescoreCommandProperties.Mode.WRITE) {
            return GuardValidation.error("Command mode is not WRITE");
        }
        if (!properties.writeEnabled()) {
            return GuardValidation.error("Score rescore write capability is disabled");
        }
        Integer expectedCount = positiveOrZero(properties.expectedChangedCount());
        if (expectedCount == null) {
            return GuardValidation.error("Expected changed count guard is missing or invalid");
        }
        String fingerprint = stripped(properties.expectedPlanFingerprint());
        if (fingerprint == null || !fingerprint.matches("(?i)[0-9a-f]{64}")) {
            return GuardValidation.error("Expected plan fingerprint guard is missing or invalid");
        }
        Integer maximumJobs = maximumJobs(properties.maxJobs());
        if (maximumJobs == null) {
            return GuardValidation.error("Maximum jobs guard is missing or invalid");
        }
        if (!CONFIRMATION.equals(properties.confirmation())) {
            return GuardValidation.error("Explicit confirmation guard is missing or invalid");
        }
        if (plan.report().inspectedJobs() > maximumJobs || plan.changedCount() > maximumJobs) {
            return GuardValidation.error("Fresh plan exceeds the maximum jobs guard");
        }
        if (expectedCount != plan.changedCount()) {
            return GuardValidation.error("Fresh plan changed count does not match its guard");
        }
        if (!fingerprint.toLowerCase(Locale.ROOT).equals(plan.fingerprint())) {
            return GuardValidation.error("Fresh plan fingerprint does not match its guard");
        }
        return GuardValidation.success(maximumJobs);
    }

    public static Integer maximumJobs(String value) {
        Integer parsed = positiveOrZero(value);
        return parsed == null || parsed < 1
                || parsed > ScoreRescorePreviewProperties.HARD_MAX_INSPECTED_JOBS ? null : parsed;
    }

    private static Integer positiveOrZero(String value) {
        String stripped = stripped(value);
        if (stripped == null || !stripped.matches("[0-9]+")) return null;
        try {
            return Integer.valueOf(stripped);
        } catch (NumberFormatException outOfRange) {
            return null;
        }
    }

    private static String stripped(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record GuardValidation(boolean valid, String safeMessage, Integer maximumJobs) {
        private static GuardValidation success(int maximumJobs) {
            return new GuardValidation(true, null, maximumJobs);
        }

        private static GuardValidation error(String safeMessage) {
            return new GuardValidation(false, safeMessage, null);
        }
    }
}
