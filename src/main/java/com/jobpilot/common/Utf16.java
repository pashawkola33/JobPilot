package com.jobpilot.common;

/** UTF-16 code-unit bounds that never leave a dangling high surrogate. */
public final class Utf16 {
    private Utf16() {
    }

    public static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        int end = Math.max(0, maximum);
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    public static String slice(String value, int startInclusive, int endExclusive) {
        int start = Math.max(0, Math.min(startInclusive, value.length()));
        int end = Math.max(start, Math.min(endExclusive, value.length()));
        if (start < end && Character.isLowSurrogate(value.charAt(start))) start++;
        if (end > start && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }
}
