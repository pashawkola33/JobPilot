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
        String requiredWorkAuthorization) {

    public LocationEligibilityDecision {
        workplaceType = workplaceType == null ? WorkplaceType.UNKNOWN : workplaceType;
        locationEligibility = locationEligibility == null
                ? LocationEligibility.REMOTE_ELIGIBILITY_UNKNOWN : locationEligibility;
        remoteScope = remoteScope == null ? RemoteScope.UNKNOWN : remoteScope;
        eligibilityReason = eligibilityReason == null ? "Eligibility could not be determined" : eligibilityReason;
        detectedLocationRestrictions = detectedLocationRestrictions == null
                ? List.of() : List.copyOf(detectedLocationRestrictions);
    }

    public boolean accepted() {
        return locationEligibility == LocationEligibility.BUCHAREST_LOCAL
                || locationEligibility == LocationEligibility.REMOTE_ROMANIA_ELIGIBLE;
    }
}
