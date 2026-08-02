package com.jobpilot.jobs.service;

import com.jobpilot.common.Hashing;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import java.time.Clock;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class JobNormalizer {
    private final Clock clock;
    private final UrlCanonicalizer urlCanonicalizer;

    public JobNormalizer(Clock clock, UrlCanonicalizer urlCanonicalizer) {
        this.clock = clock;
        this.urlCanonicalizer = urlCanonicalizer;
    }

    public Job normalize(RawJob raw) {
        return normalize(raw, null, null);
    }

    public Job normalize(RawJob raw, LocationEligibilityDecision eligibility) {
        return normalize(raw, eligibility, null);
    }

    public Job normalize(RawJob raw, LocationEligibilityDecision eligibility,
                         EarlyCareerDecision earlyCareer) {
        requireText(raw.url(), "url");
        requireText(raw.title(), "title");
        requireText(raw.company(), "company");
        String description = raw.description() == null ? "" : raw.description().strip();
        String canonical = canonicalizeUrl(raw.url());
        String normalizedLocation = eligibility == null ? normalize(raw.location())
                : normalize(eligibility.normalizedCity()) + "|"
                + normalize(eligibility.normalizedCountry()) + "|"
                + eligibility.workplaceType().name();
        String fingerprint = Hashing.sha256(normalize(raw.company()) + "|" + normalize(raw.title())
                + "|" + normalizedLocation);
        String descriptionHash = Hashing.sha256(normalize(description));
        String payload = raw.rawPayload() == null ? raw.toString() : raw.rawPayload();
        return new Job(raw.source(), blankToNull(raw.externalId()), canonical, raw.title().strip(),
                raw.company().strip(), blankToNull(raw.location()), remoteType(raw, eligibility, description),
                blankToNull(raw.employmentType()), description, raw.publishedAt(), raw.deadline(),
                Hashing.sha256(payload), descriptionHash, fingerprint, clock.instant(), eligibility,
                earlyCareer);
    }

    public String canonicalizeUrl(String raw) {
        return urlCanonicalizer.canonicalize(raw).toString();
    }

    private RemoteType remoteType(RawJob raw, LocationEligibilityDecision eligibility, String description) {
        if (eligibility != null) return RemoteType.valueOf(eligibility.workplaceType().name());
        String location = raw.location();
        String text = (String.valueOf(location) + " " + description).toLowerCase(Locale.ROOT);
        if (text.contains("hybrid")) return RemoteType.HYBRID;
        if (text.contains("remote")) return RemoteType.REMOTE;
        if (!String.valueOf(location).isBlank()) return RemoteType.ONSITE;
        return RemoteType.UNKNOWN;
    }

    private String normalize(String input) {
        return String.valueOf(input).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Job " + name + " is required");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
