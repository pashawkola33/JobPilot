package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.jobs.domain.RawJob;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class JobNormalizerTest {
    private final JobNormalizer normalizer = new JobNormalizer(
            Clock.fixed(Instant.parse("2026-07-17T12:00:00Z"), ZoneOffset.UTC),
            new UrlCanonicalizer());

    @Test
    void canonicalizesUrlAndBuildsStableHashes() {
        RawJob raw = new RawJob("greenhouse", "42",
                "HTTPS://Example.COM/jobs/../jobs/42/?utm_source=telegram&b=2&a=1#apply",
                " Java Intern ", " Example ", "Bucharest — Hybrid", "Java internship",
                "Internship", null, null, "{payload}");

        var job = normalizer.normalize(raw);

        assertThat(job.getCanonicalUrl()).isEqualTo("https://example.com/jobs/42?b=2&a=1");
        assertThat(job.getRawPayloadHash()).hasSize(64);
        assertThat(job.getDescriptionHash()).hasSize(64);
        assertThat(job.getNormalizedFingerprint()).hasSize(64);
    }

    @Test
    void rejectsIncompleteOrNonHttpJobs() {
        assertThatThrownBy(() -> normalizer.normalize(new RawJob("x", null, "file:///tmp/job",
                "Intern", "Company", "", "", null, null, null, "")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
