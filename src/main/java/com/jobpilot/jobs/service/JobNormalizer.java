package com.jobpilot.jobs.service;

import com.jobpilot.common.Hashing;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RemoteType;
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
        requireText(raw.url(), "url");
        requireText(raw.title(), "title");
        requireText(raw.company(), "company");
        String description = raw.description() == null ? "" : raw.description().strip();
        String canonical = canonicalizeUrl(raw.url());
        String fingerprint = Hashing.sha256(normalize(raw.company()) + "|" + normalize(raw.title())
                + "|" + normalize(raw.location()));
        String descriptionHash = Hashing.sha256(normalize(description));
        String payload = raw.rawPayload() == null ? raw.toString() : raw.rawPayload();
        return new Job(raw.source(), blankToNull(raw.externalId()), canonical, raw.title().strip(),
                raw.company().strip(), blankToNull(raw.location()), remoteType(raw.location(), description),
                blankToNull(raw.employmentType()), description, raw.publishedAt(), raw.deadline(),
                Hashing.sha256(payload), descriptionHash, fingerprint, clock.instant());
    }

    public String canonicalizeUrl(String raw) {
        return urlCanonicalizer.canonicalize(raw).toString();
    }

    private RemoteType remoteType(String location, String description) {
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
