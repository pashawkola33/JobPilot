package com.jobpilot.jobs.domain;

public record ExperienceRequirement(
        Double minimumYears,
        Double maximumYears,
        boolean mandatory,
        String rawText) {

    public static ExperienceRequirement unknown() {
        return new ExperienceRequirement(null, null, false, null);
    }

    public boolean known() {
        return minimumYears != null || maximumYears != null
                || rawText != null && !rawText.isBlank();
    }
}
