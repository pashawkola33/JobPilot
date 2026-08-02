package com.jobpilot.browser.application;

import com.jobpilot.browser.api.BrowserExtractionRequest;
import com.jobpilot.browser.api.BrowserExtractionResponse;
import com.jobpilot.browser.api.BrowserExtractionStatus;
import com.jobpilot.browser.api.BrowserFallbackDecision;
import com.jobpilot.browser.config.ScraperWorkerProperties;
import com.jobpilot.common.Utf16;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.manualurl.fetch.ValidatedManualUrl;
import com.jobpilot.manualurl.parse.ManualParseStatus;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Explicit, conservative browser fallback. Invoked only for a safe public
 * JS-required case (the deterministic Java fetch reached a page but could not
 * extract a vacancy, and it was not protected). The rendered content is mapped
 * onto the operator-submitted, already-SSRF-validated URL — the worker's URL and
 * any identifier are never trusted. The network call runs outside any database
 * transaction. Logs contain only an opaque request id and a fixed category.
 */
@Service
public class BrowserFallbackService {
    private static final Logger log = LoggerFactory.getLogger(BrowserFallbackService.class);
    private static final int MAX_TITLE = 300;
    private static final int MAX_COMPANY = 200;
    private static final int MAX_LOCATION = 300;
    private static final int MAX_EMPLOYMENT = 80;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScraperWorkerProperties settings;
    private final BrowserExtractionClient client;

    public BrowserFallbackService(ScraperWorkerProperties settings, BrowserExtractionClient client) {
        this.settings = settings;
        this.client = client;
    }

    public BrowserFallbackDecision decide(ManualParseStatus javaStatus) {
        if (!settings.enabled()) return BrowserFallbackDecision.SKIP_DISABLED;
        if (javaStatus == ManualParseStatus.BLOCKED_OR_PROTECTED) {
            return BrowserFallbackDecision.SKIP_PROTECTED;
        }
        if (javaStatus == ManualParseStatus.JS_RENDERING_REQUIRED) {
            return BrowserFallbackDecision.INVOKE;
        }
        return BrowserFallbackDecision.SKIP_NOT_JS_REQUIRED;
    }

    public Optional<RawJob> attempt(ValidatedManualUrl validated, ManualParseStatus javaStatus) {
        BrowserFallbackDecision decision = decide(javaStatus);
        if (decision != BrowserFallbackDecision.INVOKE) {
            log.info("Browser fallback not invoked category={}", decision);
            return Optional.empty();
        }
        String requestId = opaqueRequestId();
        BrowserExtractionResponse response;
        try {
            response = client.extract(new BrowserExtractionRequest(requestId, validated.uri().toString(), null));
        } catch (RuntimeException failure) {
            log.warn("Browser fallback failed requestId={} category=worker_error", requestId);
            return Optional.empty();
        }
        if (response.status() != BrowserExtractionStatus.EXTRACTED || response.job() == null) {
            log.info("Browser fallback returned no vacancy requestId={} category={}",
                    requestId, response.status());
            return Optional.empty();
        }
        Optional<RawJob> mapped = toRawJob(validated, response.job());
        log.info("Browser fallback completed requestId={} category={}", requestId,
                mapped.isPresent() ? "EXTRACTED" : "INSUFFICIENT");
        return mapped;
    }

    private Optional<RawJob> toRawJob(ValidatedManualUrl validated, BrowserExtractionResponse.Job job) {
        String title = bounded(job.title(), MAX_TITLE);
        String company = bounded(job.company(), MAX_COMPANY);
        String description = boundedDescription(job.description());
        if (title == null || company == null || description == null) return Optional.empty();
        // Persist against the operator-submitted, SSRF-validated URL, never Node's.
        String url = validated.uri().toString();
        return Optional.of(new RawJob(
                "browser", null, url, title, company,
                bounded(job.location(), MAX_LOCATION), description,
                bounded(job.employmentType(), MAX_EMPLOYMENT), parseInstant(job.publishedAt()), null,
                "browser-fallback"));
    }

    private String bounded(String value, int max) {
        if (value == null) return null;
        String stripped = value.strip();
        if (stripped.isEmpty() || stripped.length() > max) return null;
        return stripped;
    }

    private String boundedDescription(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        if (stripped.length() < 40) return null;
        return stripped.length() > settings.maxDescriptionCharacters()
                ? Utf16.truncate(stripped, settings.maxDescriptionCharacters()) : stripped;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException ignoredDate) {
                    return null;
                }
            }
        }
    }

    private String opaqueRequestId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
