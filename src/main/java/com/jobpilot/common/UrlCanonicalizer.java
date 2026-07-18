package com.jobpilot.common;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class UrlCanonicalizer {
    private static final int MAX_URL_LENGTH = 2_000;
    private static final Set<String> TRACKING_PARAMETERS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "ref", "referrer", "gh_src", "lever-source");

    public URI canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank() || rawUrl.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("Invalid job URL");
        }
        try {
            URI parsed = new URI(rawUrl.strip()).normalize();
            String scheme = lower(parsed.getScheme());
            String host = normalizeHost(parsed.getHost());
            if (scheme == null || host == null || !Set.of("http", "https").contains(scheme)
                    || parsed.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid job URL");
            }
            int port = normalizePort(scheme, parsed.getPort());
            String path = parsed.getRawPath();
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            String query = removeTrackingParameters(parsed.getRawQuery());
            StringBuilder canonical = new StringBuilder(scheme).append("://");
            canonical.append(host.contains(":") ? "[" + host + "]" : host);
            if (port >= 0) {
                canonical.append(':').append(port);
            }
            if (path != null) {
                canonical.append(path);
            }
            if (query != null) {
                canonical.append('?').append(query);
            }
            URI result = URI.create(canonical.toString());
            if (result.toString().length() > MAX_URL_LENGTH) {
                throw new IllegalArgumentException("Invalid job URL");
            }
            return result;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid job URL", exception);
        }
    }

    private String removeTrackingParameters(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        String filtered = Arrays.stream(rawQuery.split("&", -1))
                .filter(part -> !part.isBlank())
                .filter(part -> !TRACKING_PARAMETERS.contains(
                        part.split("=", 2)[0].toLowerCase(Locale.ROOT)))
                .collect(Collectors.joining("&"));
        return filtered.isBlank() ? null : filtered;
    }

    private int normalizePort(String scheme, int port) {
        if (port < -1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid job URL");
        }
        if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) {
            return -1;
        }
        return port;
    }

    private String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String withoutBrackets = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        if (withoutBrackets.contains(":")) {
            return withoutBrackets.toLowerCase(Locale.ROOT);
        }
        return IDN.toASCII(withoutBrackets, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
