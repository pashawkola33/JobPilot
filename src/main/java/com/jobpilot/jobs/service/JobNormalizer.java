package com.jobpilot.jobs.service;

import com.jobpilot.common.Hashing;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RemoteType;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class JobNormalizer {
    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "ref", "referrer", "source", "gh_src");
    private final Clock clock;

    public JobNormalizer(Clock clock) {
        this.clock = clock;
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
        try {
            URI uri = new URI(raw.strip()).normalize();
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme == null || host == null || !(scheme.equals("http") || scheme.equals("https"))) {
                throw new IllegalArgumentException("Invalid job URL");
            }
            String query = uri.getRawQuery();
            if (query != null) {
                query = Arrays.stream(query.split("&"))
                        .filter(part -> !TRACKING_PARAMETERS.contains(part.split("=", 2)[0].toLowerCase(Locale.ROOT)))
                        .sorted().collect(Collectors.joining("&"));
                if (query.isBlank()) {
                    query = null;
                }
            }
            String path = uri.getRawPath();
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new URI(scheme, null, host, uri.getPort(), path, query, null).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid job URL", exception);
        }
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
