package com.jobpilot.manualurl.parse;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawCareerData;
import com.jobpilot.jobs.domain.RawLocationData;
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
        ManualSourceClassification sourceClassification,
        RawLocationData locationData,
        RawCareerData careerData) {

    public ParsedManualVacancy {
        locationData = locationData == null ? RawLocationData.empty() : locationData;
        careerData = careerData == null ? RawCareerData.empty() : careerData;
    }

    public ParsedManualVacancy(String source, String externalId, String canonicalUrl, String title,
                               String company, String location, String description,
                               String employmentType, Instant publishedAt, Instant deadline,
                               ManualSourceClassification sourceClassification) {
        this(source, externalId, canonicalUrl, title, company, location, description,
                employmentType, publishedAt, deadline, sourceClassification, RawLocationData.empty(),
                RawCareerData.empty());
    }

    public ParsedManualVacancy(String source, String externalId, String canonicalUrl, String title,
                               String company, String location, String description,
                               String employmentType, Instant publishedAt, Instant deadline,
                               ManualSourceClassification sourceClassification,
                               RawLocationData locationData) {
        this(source, externalId, canonicalUrl, title, company, location, description,
                employmentType, publishedAt, deadline, sourceClassification, locationData,
                RawCareerData.empty());
    }

    public RawJob toRawJob() {
        String fingerprintInput = sourceClassification.name() + "|" + canonicalUrl + "|"
                + title + "|" + company + "|" + description;
        return new RawJob(source, externalId, canonicalUrl, title, company, location,
                description, employmentType, publishedAt, deadline, fingerprintInput, locationData,
                company, careerData);
    }
}
