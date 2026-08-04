package com.jobpilot.sources.cleanup;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Fail-closed validation for the independently enabled one-time write. */
public final class SourceLogCleanupWriteGuards {
    public static final int REQUIRED_CANDIDATE_COUNT = 7;
    public static final String CONFIRMATION =
            "RECONCILE_HISTORICAL_SOURCE_LOG_ORPHANS_ONCE";

    private SourceLogCleanupWriteGuards() {
    }

    public static Validation validate(SourceLogCleanupProperties properties,
                                      SourceLogCleanupPlan plan) {
        if (properties.mode() != SourceLogCleanupProperties.Mode.WRITE) {
            return Validation.error("COMMAND_MODE_NOT_WRITE");
        }
        if (!properties.writeEnabled()) {
            return Validation.error("WRITE_CAPABILITY_DISABLED");
        }
        if (!CONFIRMATION.equals(properties.confirmation())) {
            return Validation.error("CONFIRMATION_MISSING_OR_INVALID");
        }
        SourceLogCleanupProperties.Guards configured;
        try {
            configured = properties.guards();
        } catch (IllegalArgumentException invalidConfiguration) {
            return Validation.error("EXPECTED_SET_GUARDS_INVALID");
        }
        if (configured.expectedRunningCount() == null
                || configured.expectedRunningCount() != REQUIRED_CANDIDATE_COUNT
                || plan.expectedRunningCount() == null
                || plan.expectedRunningCount() != REQUIRED_CANDIDATE_COUNT) {
            return Validation.error("EXPECTED_COUNT_NOT_APPROVED");
        }
        List<Long> ids = plan.expectedRunningIds();
        if (ids.size() != REQUIRED_CANDIDATE_COUNT
                || !ids.equals(configured.expectedRunningIds())
                || !ids.equals(plan.observedRunningIds())) {
            return Validation.error("EXPECTED_ID_SET_MISMATCH");
        }
        List<Long> entryIds = plan.entries().stream()
                .map(SourceLogCleanupPlanEntry::id).toList();
        if (!entryIds.equals(ids) || plan.eligibleCount() != REQUIRED_CANDIDATE_COUNT
                || !plan.futureWriteEligible()) {
            return Validation.error("FRESH_PLAN_NOT_WRITE_ELIGIBLE");
        }
        if (plan.entries().stream().anyMatch(entry ->
                !SourceLogCleanupPreviewService.PROPOSED_STATUS.equals(entry.proposedStatus())
                        || !SourceLogCleanupPreviewService.PROPOSED_CATEGORY.equals(
                                entry.proposedFailureCategory())
                        || !SourceLogCleanupPreviewService.PROPOSED_FINISHED_AT_POLICY.equals(
                                entry.proposedFinishedAtPolicy())
                        || !SourceLogCleanupPreviewService.PROPOSED_ERROR_SUMMARY.equals(
                                entry.proposedErrorSummary()))) {
            return Validation.error("FRESH_PLAN_PROPOSAL_INVALID");
        }
        if (plan.maxCandidates() != configured.maxCandidates()
                || !plan.minimumAge().equals(configured.minimumAge())
                || plan.maxCandidates() < REQUIRED_CANDIDATE_COUNT
                || plan.maxCandidates() > SourceLogCleanupProperties.HARD_MAX_CANDIDATES) {
            return Validation.error("MAXIMUM_CANDIDATE_GUARD_INVALID");
        }
        String expectedFingerprint = stripped(properties.expectedPlanFingerprint());
        if (expectedFingerprint == null || !expectedFingerprint.matches("(?i)[0-9a-f]{64}")) {
            return Validation.error("FINGERPRINT_MISSING_OR_INVALID");
        }
        if (!sameFingerprint(expectedFingerprint, plan.fingerprint())) {
            return Validation.error("FRESH_PLAN_FINGERPRINT_MISMATCH");
        }
        return Validation.success();
    }

    private static boolean sameFingerprint(String expected, String actual) {
        if (actual == null || !actual.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(HexFormat.of().parseHex(expected),
                HexFormat.of().parseHex(actual));
    }

    private static String stripped(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public record Validation(boolean valid, String safeReason) {
        private static Validation success() {
            return new Validation(true, null);
        }

        private static Validation error(String safeReason) {
            return new Validation(false, safeReason);
        }
    }
}
