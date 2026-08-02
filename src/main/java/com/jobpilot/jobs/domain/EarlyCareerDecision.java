package com.jobpilot.jobs.domain;

public record EarlyCareerDecision(
        SeniorityLevel seniorityLevel,
        ExperienceRequirement experienceRequirement,
        EarlyCareerEligibility earlyCareerEligibility,
        String eligibilityReason,
        ScreeningDisposition disposition,
        java.util.List<ScreeningReason> reasons) {

    public EarlyCareerDecision {
        seniorityLevel = seniorityLevel == null ? SeniorityLevel.UNKNOWN : seniorityLevel;
        experienceRequirement = experienceRequirement == null
                ? ExperienceRequirement.unknown() : experienceRequirement;
        earlyCareerEligibility = earlyCareerEligibility == null
                ? EarlyCareerEligibility.UNKNOWN : earlyCareerEligibility;
        eligibilityReason = eligibilityReason == null || eligibilityReason.isBlank()
                ? "No seniority or experience requirement could be determined"
                : eligibilityReason.strip();
        disposition = disposition == null ? disposition(earlyCareerEligibility) : disposition;
        reasons = reasons == null || reasons.isEmpty()
                ? java.util.List.of(new ScreeningReason(ScreeningStage.CAREER_LEVEL,
                reasonCode(disposition), eligibilityReason))
                : java.util.List.copyOf(reasons);
    }

    public EarlyCareerDecision(SeniorityLevel seniorityLevel,
                               ExperienceRequirement experienceRequirement,
                               EarlyCareerEligibility earlyCareerEligibility,
                               String eligibilityReason) {
        this(seniorityLevel, experienceRequirement, earlyCareerEligibility,
                eligibilityReason, null, null);
    }

    public boolean accepted() {
        return disposition == ScreeningDisposition.MATCH;
    }

    public boolean processable() {
        return disposition.persistable();
    }

    private static ScreeningDisposition disposition(EarlyCareerEligibility eligibility) {
        return switch (eligibility) {
            case ELIGIBLE -> ScreeningDisposition.MATCH;
            case UNKNOWN -> ScreeningDisposition.REVIEW;
            case INELIGIBLE -> ScreeningDisposition.REJECT;
        };
    }

    private static String reasonCode(ScreeningDisposition disposition) {
        return switch (disposition) {
            case MATCH -> "EARLY_CAREER_MATCH";
            case REVIEW -> "CAREER_LEVEL_UNCERTAIN";
            case REJECT -> "CAREER_LEVEL_REJECTED";
        };
    }
}
