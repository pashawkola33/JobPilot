package com.jobpilot.llm.application;

import org.springframework.stereotype.Component;

@Component
public class LlmTokenEstimator {
    public long conservativeEstimate(String value) {
        if (value == null || value.isEmpty()) return 1;
        long codePoints = value.codePointCount(0, value.length());
        return Math.addExact(Math.multiplyExact(codePoints, 4L), 16L);
    }
}
