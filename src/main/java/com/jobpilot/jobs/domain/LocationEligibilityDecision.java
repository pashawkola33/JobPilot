package com.jobpilot.jobs.domain;

import java.util.List;

public record LocationEligibilityDecision(
        WorkplaceType workplaceType,
        LocationEligibility locationEligibility,
        RemoteScope remoteScope,
        String normalizedCity,
        String normalizedCountry,
        boolean eligibleFromRomania,
        String eligibilityReason,
        List<String> detectedLocationRestrictions,
        String requiredTimezone,
        String requiredWorkAuthorization,
        ScreeningDisposition disposition,
        List<ScreeningReason> reasons) {

    public LocationEligibilityDecision {
        workplaceType = workplaceType == null ? WorkplaceType.UNKNOWN : workplaceType;
        locationEligibility = locationEligibility == null
                ? LocationEligibility.REMOTE_ELIGIBILITY_UNKNOWN : locationEligibility;
        remoteScope = remoteScope == null ? RemoteScope.UNKNOWN : remoteScope;
        eligibilityReason = eligibilityReason == null ? "Eligibility could not be determined" : eligibilityReason;
        detectedLocationRestrictions = detectedLocationRestrictions == null
                ? List.of() : List.copyOf(detectedLocationRestrictions);
        disposition = disposition == null ? disposition(locationEligibility) : disposition;
        reasons = reasons == null || reasons.isEmpty()
                ? List.of(new ScreeningReason(ScreeningStage.LOCATION,
                reasonCode(disposition), eligibilityReason))
                : List.copyOf(reasons);
    }

    public LocationEligibilityDecision(
            WorkplaceType workplaceType, LocationEligibility locationEligibility,
            RemoteScope remoteScope, String normalizedCity, String normalizedCountry,
            boolean eligibleFromRomania, String eligibilityReason,
            List<String> detectedLocationRestrictions, String requiredTimezone,
            String requiredWorkAuthorization) {
        this(workplaceType, locationEligibility, remoteScope, normalizedCity, normalizedCountry,
                eligibleFromRomania, eligibilityReason, detectedLocationRestrictions,
                requiredTimezone, requiredWorkAuthorization, null, null);
    }

    public boolean accepted() {
        return disposition == ScreeningDisposition.MATCH;
    }

    public boolean processable() {
        return disposition.persistable();
    }

    private static ScreeningDisposition disposition(LocationEligibility eligibility) {
        return switch (eligibility) {
            case BUCHAREST_LOCAL, REMOTE_ROMANIA_ELIGIBLE -> ScreeningDisposition.MATCH;
            case REMOTE_ELIGIBILITY_UNKNOWN -> ScreeningDisposition.REVIEW;
            case REJECTED_LOCATION -> ScreeningDisposition.REJECT;
        };
    }

    private static String reasonCode(ScreeningDisposition disposition) {
        return switch (disposition) {
            case MATCH -> "LOCATION_MATCH";
            case REVIEW -> "LOCATION_UNCERTAIN";
            case REJECT -> "LOCATION_REJECTED";
        };
    }
}
