package com.jobpilot.jobs.domain;

import java.time.Instant;

public record RawJob(
        String source,
        String externalId,
        String url,
        String title,
        String company,
        String location,
        String description,
        String employmentType,
        Instant publishedAt,
        Instant deadline,
        String rawPayload) {
}
