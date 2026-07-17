package com.jobpilot.jobs.domain;

import java.time.Instant;
import java.util.List;

public record ExtractedRequirements(
        String seniority,
        boolean internshipOrTrainee,
        Double requiredExperienceYears,
        String requiredEducation,
        boolean finalYearMandatory,
        List<String> technologies,
        List<String> programmingLanguages,
        List<String> spokenLanguages,
        String location,
        String remoteEligibility,
        List<String> mentorshipSignals,
        String workAuthorization,
        String salary,
        Instant applicationDeadline,
        String extractionMethod) {
}
