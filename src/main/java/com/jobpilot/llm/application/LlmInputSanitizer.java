package com.jobpilot.llm.application;

import org.springframework.stereotype.Component;

@Component
public class LlmInputSanitizer {
    public String clean(String value, int maximum) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder(Math.min(value.length(), maximum));
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (type == Character.FORMAT) continue;
            if (type == Character.CONTROL && codePoint != '\n' && codePoint != '\t') {
                if (safe.length() < maximum) safe.append(' ');
                continue;
            }
            if (safe.length() + Character.charCount(codePoint) > maximum) break;
            safe.appendCodePoint(codePoint);
        }
        return safe.toString().strip();
    }
}
