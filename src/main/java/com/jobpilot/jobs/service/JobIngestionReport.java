package com.jobpilot.jobs.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        int locationAndCareerEligibleVacancies,
        int relevanceMatchVacancies,
        int relevanceReviewVacancies,
        int rejectedByRelevance,
        int finalMatchVacancies,
        int finalReviewVacancies,
        int finalRejectVacancies,
        int duplicateRawVacancies,
        int existingUnchangedVacancies,
        int persistedNewVacancies,
        int updatedVacancies,
        Map<String, List<String>> locationAndCareerEligibleTenantsByProvider,
        Map<String, List<String>> finalMatchTenantsByProvider,
        Map<String, List<String>> finalReviewTenantsByProvider) {

    public JobIngestionReport {
        locationAndCareerEligibleTenantsByProvider = immutable(locationAndCareerEligibleTenantsByProvider);
        finalMatchTenantsByProvider = immutable(finalMatchTenantsByProvider);
        finalReviewTenantsByProvider = immutable(finalReviewTenantsByProvider);
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

    public boolean eligibleTargetMet() {
        return locationEligibleTargetMet();
    }

    public int eligibleShortfall() {
        return Math.max(0, 150 - locationEligibleVacancies());
    }

    public int notificationEligibleVacancies() {
        return finalMatchVacancies;
    }

    public int estimatedAdditionalRelevantTenantBoardsNeeded() {
        if (locationEligibleTargetMet()) return 0;
        int producingTenants = locationAndCareerEligibleTenantsByProvider.values().stream()
                .mapToInt(List::size).sum();
        if (producingTenants == 0 || locationEligibleVacancies() == 0) return eligibleShortfall();
        double averageYield = (double) locationEligibleVacancies() / producingTenants;
        return (int) Math.ceil(eligibleShortfall() / averageYield);
    }

    private static Map<String, List<String>> immutable(Map<String, List<String>> source) {
        if (source == null) return Map.of();
        return source.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
