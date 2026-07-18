package com.jobpilot.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlCanonicalizerTest {
    private final UrlCanonicalizer canonicalizer = new UrlCanonicalizer();

    @Test
    void normalizesHostDefaultPortPathAndFragment() {
        assertThat(canonicalizer.canonicalize(
                "HTTPS://Example.COM:443/jobs/../jobs/42/#apply").toString())
                .isEqualTo("https://example.com/jobs/42");
    }

    @Test
    void removesKnownTrackingParametersWithoutReorderingMeaningfulParameters() {
        assertThat(canonicalizer.canonicalize(
                "https://example.com/job?b=2&utm_source=telegram&a=1&gh_src=feed").toString())
                .isEqualTo("https://example.com/job?b=2&a=1");
    }
}
