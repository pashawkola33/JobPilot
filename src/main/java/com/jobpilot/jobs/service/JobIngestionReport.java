package com.jobpilot.jobs.service;

import java.util.List;
import java.util.Map;

public record JobIngestionReport(
        int totalVacanciesFetched,
        int totalUniqueVacanciesBeforeEligibilityFiltering,
        int bucharestLocalVacancies,
        int remoteVacanciesEligibleFromRomania,
        int remoteEligibilityUnknown,
        int rejectedByGeographicRestriction,
        int rejectedOnsiteOrHybridOutsideBucharest,
        int earlyCareerEligibleVacancies,
        int earlyCareerEligibilityUnknown,
        int rejectedBySeniorityOrExperience,
        int finalUniqueEligibleVacancies,
        Map<String, List<String>> eligibleTenantsByProvider) {

    public JobIngestionReport {
        eligibleTenantsByProvider = eligibleTenantsByProvider == null ? Map.of()
                : eligibleTenantsByProvider.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }

    public JobIngestionReport(int totalVacanciesFetched,
                              int totalUniqueVacanciesBeforeEligibilityFiltering,
                              int bucharestLocalVacancies,
                              int remoteVacanciesEligibleFromRomania,
                              int remoteEligibilityUnknown,
                              int rejectedByGeographicRestriction,
                              int rejectedOnsiteOrHybridOutsideBucharest,
                              int finalUniqueEligibleVacancies,
                              Map<String, List<String>> eligibleTenantsByProvider) {
        this(totalVacanciesFetched, totalUniqueVacanciesBeforeEligibilityFiltering,
                bucharestLocalVacancies, remoteVacanciesEligibleFromRomania,
                remoteEligibilityUnknown, rejectedByGeographicRestriction,
                rejectedOnsiteOrHybridOutsideBucharest, finalUniqueEligibleVacancies,
                0, 0, finalUniqueEligibleVacancies, eligibleTenantsByProvider);
    }

    public boolean rawTargetMet() {
        return totalUniqueVacanciesBeforeEligibilityFiltering >= 500;
    }

    public int locationEligibleVacancies() {
        return bucharestLocalVacancies + remoteVacanciesEligibleFromRomania;
    }

    public boolean locationEligibleTargetMet() {
        return locationEligibleVacancies() >= 150;
    }

    /** Compatibility alias for the original location-volume target. */
    public boolean eligibleTargetMet() {
        return locationEligibleTargetMet();
    }

    public int eligibleShortfall() {
        return Math.max(0, 150 - locationEligibleVacancies());
    }

    /** Estimate based on the current average yield of boards that produced eligible jobs. */
    public int estimatedAdditionalRelevantTenantBoardsNeeded() {
        if (locationEligibleTargetMet()) return 0;
        int producingTenants = eligibleTenantsByProvider.values().stream().mapToInt(List::size).sum();
        if (producingTenants == 0 || locationEligibleVacancies() == 0) return eligibleShortfall();
        double averageYield = (double) locationEligibleVacancies() / producingTenants;
        return (int) Math.ceil(eligibleShortfall() / averageYield);
    }
}
