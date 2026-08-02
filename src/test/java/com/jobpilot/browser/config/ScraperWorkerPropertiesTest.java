package com.jobpilot.browser.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ScraperWorkerPropertiesTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void disabledRequiresNoSecretOrUrl() {
        ScraperWorkerProperties disabled = ScraperWorkerProperties.disabled();
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void enabledFailsClosedWithoutSecretOrValidUrl() {
        assertThatThrownBy(() -> enabled("http://scraper-worker:3000", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled("http://scraper-worker:3000", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled("ftp://scraper-worker:3000", SECRET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled("http://user:pw@scraper-worker:3000", SECRET))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enabled("http://scraper-worker:3000/x?q=1", SECRET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enabledBuildsTheFixedExtractEndpoint() {
        ScraperWorkerProperties props = enabled("http://scraper-worker:3000/", SECRET);
        assertThat(props.extractEndpoint().toString()).isEqualTo("http://scraper-worker:3000/v1/extract");
    }

    @Test
    void rejectsResponseTimeoutNotExceedingConnect() {
        assertThatThrownBy(() -> new ScraperWorkerProperties(true, "http://scraper-worker:3000", SECRET,
                Duration.ofSeconds(10), Duration.ofSeconds(5), 1_048_576, 50_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ScraperWorkerProperties enabled(String baseUrl, String secret) {
        return new ScraperWorkerProperties(true, baseUrl, secret, Duration.ofSeconds(5),
                Duration.ofSeconds(45), 1_048_576, 50_000);
    }
}
