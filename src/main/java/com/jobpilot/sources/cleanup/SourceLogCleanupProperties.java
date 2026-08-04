package com.jobpilot.sources.cleanup;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Default-off settings for the guarded historical source-log one-shot command. */
@ConfigurationProperties("jobpilot.source-log-cleanup")
public record SourceLogCleanupProperties(
        Mode mode,
        boolean writeEnabled,
        Duration minimumAge,
        Integer maxCandidates,
        String expectedRunningIds,
        String expectedRunningCount,
        String expectedPlanFingerprint,
        String confirmation) {

    public static final Duration DEFAULT_MINIMUM_AGE = Duration.ofHours(6);
    public static final int DEFAULT_MAX_CANDIDATES = 20;
    public static final int HARD_MAX_CANDIDATES = 100;

    public enum Mode { OFF, PREVIEW, WRITE }

    @ConstructorBinding
    public SourceLogCleanupProperties {
        mode = mode == null ? Mode.OFF : mode;
        minimumAge = minimumAge == null ? DEFAULT_MINIMUM_AGE : minimumAge;
        maxCandidates = maxCandidates == null ? DEFAULT_MAX_CANDIDATES : maxCandidates;
    }

    SourceLogCleanupProperties(Mode mode, Duration minimumAge, Integer maxCandidates,
                               String expectedRunningIds, String expectedRunningCount) {
        this(mode, false, minimumAge, maxCandidates, expectedRunningIds,
                expectedRunningCount, null, null);
    }

    /** Parses all operator guards at command execution time, never while mode is OFF. */
    public Guards guards() {
        if (minimumAge.isZero() || minimumAge.isNegative()) {
            throw new IllegalArgumentException("Minimum candidate age must be positive");
        }
        if (maxCandidates < 1 || maxCandidates > HARD_MAX_CANDIDATES) {
            throw new IllegalArgumentException("Maximum candidates is outside the safe range");
        }
        List<Long> ids = parseIds(expectedRunningIds);
        if (ids.size() > HARD_MAX_CANDIDATES) {
            throw new IllegalArgumentException("Expected RUNNING IDs exceed hard maximum");
        }
        Integer count = parseCount(expectedRunningCount);
        if (ids.isEmpty() && (count == null || count != 0)) {
            throw new IllegalArgumentException(
                    "Expected RUNNING IDs are required unless expected count is zero");
        }
        if (count != null && count != ids.size()) {
            throw new IllegalArgumentException("Expected RUNNING count conflicts with expected IDs");
        }
        return new Guards(minimumAge, maxCandidates, ids, count);
    }

    private static List<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String value = part.strip();
            if (!value.matches("[1-9]\\d*")) {
                throw new IllegalArgumentException("Expected RUNNING IDs are malformed");
            }
            try {
                if (!unique.add(Long.parseLong(value))) {
                    throw new IllegalArgumentException("Expected RUNNING IDs contain duplicates");
                }
            } catch (NumberFormatException tooLarge) {
                throw new IllegalArgumentException("Expected RUNNING IDs are malformed");
            }
        }
        ArrayList<Long> ordered = new ArrayList<>(unique);
        ordered.sort(Long::compareTo);
        return List.copyOf(ordered);
    }

    private static Integer parseCount(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip();
        if (!value.matches("\\d+")) {
            throw new IllegalArgumentException("Expected RUNNING count is malformed");
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > HARD_MAX_CANDIDATES) {
                throw new IllegalArgumentException("Expected RUNNING count exceeds hard maximum");
            }
            return parsed;
        } catch (NumberFormatException tooLarge) {
            throw new IllegalArgumentException("Expected RUNNING count is malformed");
        }
    }

    public record Guards(Duration minimumAge, int maxCandidates, List<Long> expectedRunningIds,
                         Integer expectedRunningCount) {
        public Guards {
            expectedRunningIds = List.copyOf(expectedRunningIds);
        }
    }
}
