package com.jobpilot.candidate.domain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class SimpleValueCollection {
    private SimpleValueCollection() {
    }

    static String encode(List<String> values) {
        var normalized = new LinkedHashMap<String, String>();
        values.stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(value -> normalized.putIfAbsent(value.toLowerCase(Locale.ROOT), value));
        return String.join("|", normalized.values());
    }

    static List<String> decode(String value) {
        return value == null || value.isBlank()
                ? List.of()
                : Arrays.stream(value.split("\\|", -1)).filter(item -> !item.isBlank()).toList();
    }
}
