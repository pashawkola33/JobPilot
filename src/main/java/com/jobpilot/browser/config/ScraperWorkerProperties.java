package com.jobpilot.browser.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional configuration for the browser-rendering fallback worker. Disabled by
 * default. When enabled it fails startup closed unless a valid worker base URL
 * and a sufficiently strong shared secret are present. The worker is a separate
 * internal service; its base URL is a trusted Compose destination, not a
 * user-supplied URL.
 */
@ConfigurationProperties("jobpilot.scraper-worker")
public record ScraperWorkerProperties(
        boolean enabled,
        String baseUrl,
        String sharedSecret,
        Duration connectTimeout,
        Duration responseTimeout,
        int maxResponseBytes,
        int maxDescriptionCharacters) {

    private static final int MIN_SECRET_BYTES = 32;

    public ScraperWorkerProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        sharedSecret = sharedSecret == null ? "" : sharedSecret.strip();
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        responseTimeout = responseTimeout == null ? Duration.ofSeconds(45) : responseTimeout;
        if (connectTimeout.isZero() || connectTimeout.isNegative()
                || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || responseTimeout.isZero() || responseTimeout.isNegative()
                || responseTimeout.compareTo(Duration.ofSeconds(90)) > 0
                || responseTimeout.compareTo(connectTimeout) <= 0) {
            throw new IllegalArgumentException("Scraper-worker timeouts are outside their safe bounds");
        }
        if (maxResponseBytes < 1_024 || maxResponseBytes > 10 * 1_024 * 1_024
                || maxDescriptionCharacters < 40 || maxDescriptionCharacters > 200_000) {
            throw new IllegalArgumentException("Scraper-worker limits are outside their safe bounds");
        }
        if (enabled) {
            validateBaseUrl(baseUrl);
            if (sharedSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
                throw new IllegalArgumentException(
                        "An enabled scraper worker requires a shared secret of at least 32 bytes");
            }
        }
    }

    public static ScraperWorkerProperties disabled() {
        return new ScraperWorkerProperties(false, "", "", Duration.ofSeconds(5),
                Duration.ofSeconds(45), 1_048_576, 50_000);
    }

    /** The single fixed extraction endpoint. The client never targets any other host. */
    public URI extractEndpoint() {
        if (!enabled) throw new IllegalStateException("Scraper worker is disabled");
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + "/v1/extract");
    }

    private static void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("An enabled scraper worker requires a valid base URL");
        }
    }
}
