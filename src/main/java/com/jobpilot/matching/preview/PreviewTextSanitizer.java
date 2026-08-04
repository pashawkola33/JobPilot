package com.jobpilot.matching.preview;

import com.jobpilot.common.Hashing;
import com.jobpilot.common.Utf16;
import java.util.ArrayList;
import java.util.List;

/** Binds every persisted text field before it can reach operational logs. */
final class PreviewTextSanitizer {
    private static final int EXTERNAL_ID_PREFIX = 32;
    private static final int EXTERNAL_ID_HASH = 12;

    private PreviewTextSanitizer() {
    }

    static String text(String value, int maximum) {
        if (value == null) return "<missing>";
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maximum));
        boolean previousWhitespace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                codePoint = ' ';
            }
            if (Character.isWhitespace(codePoint)) {
                if (!previousWhitespace && safe.length() > 0) safe.append(' ');
                previousWhitespace = true;
            } else {
                safe.appendCodePoint(codePoint);
                previousWhitespace = false;
            }
            if (safe.length() >= maximum) break;
        }
        String normalized = Utf16.truncate(safe.toString().strip(), maximum);
        return normalized.isEmpty() ? "<blank>" : normalized;
    }

    static String externalId(String value) {
        if (value == null || value.isBlank()) return "<missing>";
        String normalized = text(value, 512);
        if (normalized.length() <= 48 && value.length() <= 48) return normalized;
        String prefix = Utf16.truncate(normalized, EXTERNAL_ID_PREFIX);
        return prefix + "...#" + Hashing.sha256(value).substring(0, EXTERNAL_ID_HASH);
    }

    static List<String> list(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> safe = new ArrayList<>();
        values.stream().limit(5).forEach(value -> safe.add(text(value, 100)));
        return List.copyOf(safe);
    }
}
