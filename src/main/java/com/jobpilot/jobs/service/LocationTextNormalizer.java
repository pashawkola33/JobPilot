package com.jobpilot.jobs.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical text form used by location screening.
 *
 * <p>Extracted in Phase 3.2.6 so the three regular expressions are compiled once at class
 * initialisation instead of on every {@code String.replaceAll} call, and so tests can
 * substitute a counting implementation to prove each field is normalised a bounded number
 * of times per vacancy. The semantics are byte-for-byte those of the previous private
 * helper: NFD decomposition, combining-mark removal, lower-casing with {@link Locale#ROOT},
 * collapsing every run of characters outside letters/digits/{@code +}/{@code -} to a single
 * space, collapsing whitespace runs, and trimming.
 */
interface LocationTextNormalizer {

    String normalize(String value);

    static LocationTextNormalizer standard() {
        return Standard.INSTANCE;
    }

    /**
     * Normalized text plus the derived forms every phrase check needs, so a phrase test
     * never re-normalizes and never rebuilds the padded string.
     *
     * @param value  normalized text
     * @param padded {@code " " + value + " "}, matching the previous word() padding
     * @param tokens whitespace-separated tokens of {@code value}, used only to prune
     *               impossible phrase matches; pruning never changes an outcome
     */
    record NormalizedText(String value, String padded, Set<String> tokens) {

        static NormalizedText of(String normalized) {
            String safe = normalized == null ? "" : normalized;
            Set<String> tokens = safe.isEmpty()
                    ? Set.of() : new HashSet<>(Arrays.asList(SPACE.split(safe)));
            return new NormalizedText(safe, " " + safe + " ", tokens);
        }

        boolean isBlank() {
            return value.isEmpty();
        }

        /** True when {@code value} contains {@code substring} anywhere, no boundary. */
        boolean contains(String substring) {
            return value.contains(substring);
        }

        /** True when every supplied token occurs as a whole token in this text. */
        boolean containsAllTokens(List<String> requiredTokens) {
            for (String token : requiredTokens) {
                if (!tokens.contains(token)) return false;
            }
            return true;
        }

        /**
         * Exactly the previous {@code word(text, needle)} semantics: the needle must
         * appear delimited by the padded boundaries.
         *
         * @param normalizedNeedle a needle already in normalized form
         */
        boolean containsPhrase(String normalizedNeedle) {
            if (normalizedNeedle == null || normalizedNeedle.isEmpty()) {
                // " " + text + " " can only contain "  " when the text itself is empty.
                return value.isEmpty();
            }
            // A phrase can only match if its first token is present as a whole token, so
            // this prunes without altering any result.
            int firstSpace = normalizedNeedle.indexOf(' ');
            String firstToken = firstSpace < 0
                    ? normalizedNeedle : normalizedNeedle.substring(0, firstSpace);
            if (!tokens.contains(firstToken)) return false;
            return padded.contains(" " + normalizedNeedle + " ");
        }
    }

    Pattern SPACE = Pattern.compile(" ");

    /** Production implementation; the patterns below are compiled exactly once. */
    final class Standard implements LocationTextNormalizer {
        static final Standard INSTANCE = new Standard();

        private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
        private static final Pattern NON_TOKEN = Pattern.compile("[^\\p{L}\\p{N}+-]+");
        private static final Pattern WHITESPACE = Pattern.compile("\\s+");

        private Standard() {
        }

        @Override
        public String normalize(String value) {
            String safe = value == null ? "" : value;
            String decomposed = COMBINING_MARKS
                    .matcher(Normalizer.normalize(safe, Normalizer.Form.NFD)).replaceAll("");
            String collapsed = NON_TOKEN
                    .matcher(decomposed.toLowerCase(Locale.ROOT)).replaceAll(" ");
            return WHITESPACE.matcher(collapsed).replaceAll(" ").trim();
        }
    }
}
