package com.jobpilot.matching;

import com.jobpilot.config.JobPilotProperties;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable candidate snapshot consumed by the deterministic scorer.
 *
 * <p>It is deliberately not the persistent {@code com.jobpilot.candidate.CandidateProfile}, not a
 * JPA entity, and not an account: it carries only the candidate values the current scoring
 * algorithm reads, so that scoring becomes an explicit function of a candidate rather than of one
 * globally configured one. Scoring must never fetch it from a repository.
 *
 * <p>Both skill sets are normalized to lowercase and deduplicated at construction. The scorer
 * matches lowercased requirement technologies against them and divides by {@code
 * backendSkills.size()}, so normalization belongs to the value rather than to the scorer.
 */
public record CandidateMatchingProfile(
        String homeCountry,
        Set<String> backendSkills,
        Set<String> supportingSkills,
        boolean currentStudent,
        boolean finalYearStudent,
        double commercialJavaYears) {

    public CandidateMatchingProfile {
        homeCountry = requireText(homeCountry, "Candidate home country");
        backendSkills = normalize(backendSkills, "Candidate backend skills");
        supportingSkills = normalize(supportingSkills, "Candidate supporting skills");
        if (!Double.isFinite(commercialJavaYears) || commercialJavaYears < 0) {
            throw new IllegalArgumentException(
                    "Candidate commercial Java years must be a finite, non-negative number");
        }
    }

    /** Compatibility bridge from the single configured candidate in application.yml. */
    public static CandidateMatchingProfile fromLegacy(JobPilotProperties.Candidate candidate) {
        Objects.requireNonNull(candidate, "Legacy candidate configuration is required");
        return new CandidateMatchingProfile(candidate.homeCountry(),
                asSet(candidate.backendSkills(), "Candidate backend skills"),
                asSet(candidate.supportingSkills(), "Candidate supporting skills"),
                candidate.currentStudent(), candidate.finalYearStudent(),
                candidate.commercialJavaYears());
    }

    /** Configured lists may repeat an entry; deduplication belongs to normalization, not binding. */
    private static Set<String> asSet(Collection<String> values, String name) {
        Objects.requireNonNull(values, name + " are required");
        return new LinkedHashSet<>(values);
    }

    private static Set<String> normalize(Collection<String> values, String name) {
        Objects.requireNonNull(values, name + " are required");
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(Objects.requireNonNull(value, name + " must not contain null entries")
                    .toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
