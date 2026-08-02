package com.jobpilot.jobs.domain;

/** Structured seniority and experience facts supplied by a provider adapter. */
public record RawCareerData(
        String providerSeniority,
        Double minimumYears,
        Double maximumYears,
        Boolean mandatory,
        String rawExperienceText) {

    public static RawCareerData empty() {
        return new RawCareerData(null, null, null, null, null);
    }

    public boolean hasExperienceRequirement() {
        return minimumYears != null || maximumYears != null
                || rawExperienceText != null && !rawExperienceText.isBlank();
    }
}
