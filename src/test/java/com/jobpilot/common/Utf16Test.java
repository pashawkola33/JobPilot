package com.jobpilot.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Utf16Test {
    @Test
    void neverCutsBetweenSupplementaryCharacterSurrogates() {
        String value = "abcd🚀tail";

        assertThat(Utf16.truncate(value, 5)).isEqualTo("abcd");
        assertThat(Utf16.truncate(value, 6)).isEqualTo("abcd🚀");
    }

    @Test
    void rangeSliceNeverReturnsHalfOfASupplementaryCharacter() {
        String value = "head🚀tail";

        assertThat(Utf16.slice(value, 0, 5)).isEqualTo("head");
        assertThat(Utf16.slice(value, 5, value.length())).isEqualTo("tail");
    }
}
