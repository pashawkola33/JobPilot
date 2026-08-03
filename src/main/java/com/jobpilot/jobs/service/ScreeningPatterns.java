package com.jobpilot.jobs.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Bounded cache of screening regular expressions.
 *
 * <p>Phase 3.2.6 removed the location hotspot; the adjacent early-career and relevance
 * predicates on the same per-vacancy path were recompiling fixed expressions on every
 * call. Keys here are always fixed application vocabulary — regex literals from the code
 * and phrases from the candidate profile configuration — never job titles, descriptions,
 * or any other per-vacancy text, so the cache cannot grow with ingestion volume. The hard
 * cap is a defensive backstop: past it, callers simply compile as before rather than
 * retaining anything unbounded.
 */
final class ScreeningPatterns {
    /** Comfortably above the fixed vocabulary; never reached in normal operation. */
    private static final int MAX_ENTRIES = 512;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final ConcurrentMap<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private ScreeningPatterns() {
    }

    /**
     * Case-insensitive, Unicode-aware pattern for a fixed expression.
     *
     * @param regex a compile-time literal or configured phrase, never per-vacancy text
     */
    static Pattern caseInsensitive(String regex) {
        Pattern cached = CACHE.get(regex);
        if (cached != null) return cached;
        Pattern compiled = Pattern.compile(regex, FLAGS);
        if (CACHE.size() < MAX_ENTRIES) CACHE.putIfAbsent(regex, compiled);
        return compiled;
    }

    /** Visible for tests: how many distinct expressions have been retained. */
    static int cachedCount() {
        return CACHE.size();
    }
}
