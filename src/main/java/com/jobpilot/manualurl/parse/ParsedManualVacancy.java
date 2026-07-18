package com.jobpilot.manualurl.parse;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.manualurl.domain.ManualSourceClassification;
import java.time.Instant;

public record ParsedManualVacancy(
        String source,
        String externalId,
        String canonicalUrl,
        String title,
        String company,
        String location,
        String description,
        String employmentType,
        Instant publishedAt,
        Instant deadline,
        ManualSourceClassification sourceClassification) {

    public RawJob toRawJob() {
        String fingerprintInput = sourceClassification.name() + "|" + canonicalUrl + "|"
                + title + "|" + company + "|" + description;
        return new RawJob(source, externalId, canonicalUrl, title, company, location,
                description, employmentType, publishedAt, deadline, fingerprintInput);
    }
}
