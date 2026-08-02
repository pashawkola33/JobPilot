package com.jobpilot.jobs.domain;

import java.util.List;

/** Provider-supplied location facts. Final eligibility is deliberately decided elsewhere. */
public record RawLocationData(
        String workplaceType,
        List<String> structuredLocations,
        List<String> remoteRegions,
        String jobLocationType,
        List<String> applicantLocationRequirements,
        String requiredTimezone,
        String requiredWorkAuthorization) {

    public RawLocationData {
        structuredLocations = copy(structuredLocations);
        remoteRegions = copy(remoteRegions);
        applicantLocationRequirements = copy(applicantLocationRequirements);
    }

    public static RawLocationData empty() {
        return new RawLocationData(null, List.of(), List.of(), null, List.of(), null, null);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::strip).toList();
    }
}
