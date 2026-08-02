package com.jobpilot.jobs.domain;

public record EarlyCareerDecision(
        SeniorityLevel seniorityLevel,
        ExperienceRequirement experienceRequirement,
        EarlyCareerEligibility earlyCareerEligibility,
        String eligibilityReason) {

    public EarlyCareerDecision {
        seniorityLevel = seniorityLevel == null ? SeniorityLevel.UNKNOWN : seniorityLevel;
        experienceRequirement = experienceRequirement == null
                ? ExperienceRequirement.unknown() : experienceRequirement;
        earlyCareerEligibility = earlyCareerEligibility == null
                ? EarlyCareerEligibility.UNKNOWN : earlyCareerEligibility;
        eligibilityReason = eligibilityReason == null || eligibilityReason.isBlank()
                ? "No seniority or experience requirement could be determined"
                : eligibilityReason.strip();
    }

    public boolean accepted() {
        return earlyCareerEligibility == EarlyCareerEligibility.ELIGIBLE;
    }
}
