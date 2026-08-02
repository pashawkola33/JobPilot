package com.jobpilot.browser.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.browser.api.BrowserExtractionResponse;
import com.jobpilot.browser.api.BrowserExtractionStatus;
import com.jobpilot.browser.api.BrowserFallbackDecision;
import com.jobpilot.browser.config.ScraperWorkerProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.manualurl.fetch.ValidatedManualUrl;
import com.jobpilot.manualurl.parse.ManualParseStatus;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BrowserFallbackServiceTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final ValidatedManualUrl validated = validated("https://93.184.216.34/jobs/42");

    @Test
    void disabledWorkerMakesNoRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BrowserFallbackService service = new BrowserFallbackService(
                ScraperWorkerProperties.disabled(),
                request -> {
                    calls.incrementAndGet();
                    return BrowserExtractionResponse.unavailable();
                });
        assertThat(service.decide(ManualParseStatus.JS_RENDERING_REQUIRED))
                .isEqualTo(BrowserFallbackDecision.SKIP_DISABLED);
        assertThat(service.attempt(validated, ManualParseStatus.JS_RENDERING_REQUIRED)).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void protectedPageNeverInvokesTheWorker() {
        AtomicInteger calls = new AtomicInteger();
        BrowserFallbackService service = enabled(request -> {
            calls.incrementAndGet();
            return BrowserExtractionResponse.unavailable();
        });
        assertThat(service.decide(ManualParseStatus.BLOCKED_OR_PROTECTED))
                .isEqualTo(BrowserFallbackDecision.SKIP_PROTECTED);
        assertThat(service.attempt(validated, ManualParseStatus.BLOCKED_OR_PROTECTED)).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void jsRequiredPageInvokesTheWorkerOnceAndMapsExtracted() {
        AtomicInteger calls = new AtomicInteger();
        BrowserExtractionResponse extracted = new BrowserExtractionResponse(
                BrowserExtractionStatus.EXTRACTED, "https://elsewhere.example/x", "BROWSER",
                new BrowserExtractionResponse.Job("Java Backend Intern", "Example Company",
                        "Bucharest, Romania",
                        "Java Spring Boot internship building REST APIs with mentorship and tests.",
                        "INTERN", "2026-07-19", "https://elsewhere.example/x"),
                new BrowserExtractionResponse.Evidence("JSON_LD", "JSON_LD", "JSON_LD"));
        BrowserFallbackService service = enabled(request -> {
            calls.incrementAndGet();
            return extracted;
        });

        Optional<RawJob> job = service.attempt(validated, ManualParseStatus.JS_RENDERING_REQUIRED);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(job).isPresent();
        job.ifPresent(value -> {
            assertThat(value.title()).isEqualTo("Java Backend Intern");
            assertThat(value.company()).isEqualTo("Example Company");
            // The persisted URL is the operator-submitted one, never Node's.
            assertThat(value.url()).isEqualTo("https://93.184.216.34/jobs/42");
            assertThat(value.externalId()).isNull();
            assertThat(value.source()).isEqualTo("browser");
        });
    }

    @Test
    void nonExtractedWorkerOutcomesYieldNoVacancy() {
        for (BrowserExtractionStatus status : List.of(
                BrowserExtractionStatus.AUTH_REQUIRED, BrowserExtractionStatus.CHALLENGE_DETECTED,
                BrowserExtractionStatus.RATE_LIMITED, BrowserExtractionStatus.INVALID_URL,
                BrowserExtractionStatus.INSUFFICIENT_DATA, BrowserExtractionStatus.WORKER_UNAVAILABLE)) {
            BrowserFallbackService service = enabled(
                    request -> new BrowserExtractionResponse(status, null, null, null, null));
            assertThat(service.attempt(validated, ManualParseStatus.JS_RENDERING_REQUIRED))
                    .as(status.name()).isEmpty();
        }
    }

    @Test
    void genericParseFailuresAndUnsupportedPagesNeverInvokeTheWorker() {
        AtomicInteger calls = new AtomicInteger();
        BrowserFallbackService service = enabled(request -> {
            calls.incrementAndGet();
            return BrowserExtractionResponse.unavailable();
        });

        for (ManualParseStatus status : List.of(
                ManualParseStatus.PARSE_FAILED, ManualParseStatus.UNSUPPORTED_SOURCE)) {
            assertThat(service.decide(status)).isEqualTo(BrowserFallbackDecision.SKIP_NOT_JS_REQUIRED);
            assertThat(service.attempt(validated, status)).isEmpty();
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    void descriptionTruncationIsUtf16Safe() {
        String description = "a".repeat(49_999) + "🚀" + "tail";
        BrowserExtractionResponse extracted = new BrowserExtractionResponse(
                BrowserExtractionStatus.EXTRACTED, null, "BROWSER",
                new BrowserExtractionResponse.Job("Intern", "Example", "Bucharest", description,
                        "Internship", null, null), null);

        RawJob job = enabled(request -> extracted)
                .attempt(validated, ManualParseStatus.JS_RENDERING_REQUIRED).orElseThrow();

        assertThat(job.description()).hasSize(49_999).doesNotEndWith("\uD83D");
    }

    private BrowserFallbackService enabled(BrowserExtractionClient client) {
        ScraperWorkerProperties props = new ScraperWorkerProperties(true, "http://scraper-worker:3000",
                SECRET, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(45),
                1_048_576, 50_000);
        return new BrowserFallbackService(props, client);
    }

    private ValidatedManualUrl validated(String url) {
        try {
            return new ValidatedManualUrl(URI.create(url), List.of(InetAddress.getByName("93.184.216.34")));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
