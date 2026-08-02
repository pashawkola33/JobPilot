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
        String rawPayload,
        RawLocationData locationData,
        String providerTenant,
        RawCareerData careerData) {

    public RawJob {
        locationData = locationData == null ? RawLocationData.empty() : locationData;
        providerTenant = providerTenant == null || providerTenant.isBlank() ? company : providerTenant.strip();
        careerData = careerData == null ? RawCareerData.empty() : careerData;
    }

    public RawJob(String source, String externalId, String url, String title, String company,
                  String location, String description, String employmentType, Instant publishedAt,
                  Instant deadline, String rawPayload) {
        this(source, externalId, url, title, company, location, description, employmentType,
                publishedAt, deadline, rawPayload, RawLocationData.empty(), company, RawCareerData.empty());
    }

    public RawJob(String source, String externalId, String url, String title, String company,
                  String location, String description, String employmentType, Instant publishedAt,
                  Instant deadline, String rawPayload, RawLocationData locationData) {
        this(source, externalId, url, title, company, location, description, employmentType,
                publishedAt, deadline, rawPayload, locationData, company, RawCareerData.empty());
    }

    public RawJob(String source, String externalId, String url, String title, String company,
                  String location, String description, String employmentType, Instant publishedAt,
                  Instant deadline, String rawPayload, RawLocationData locationData,
                  String providerTenant) {
        this(source, externalId, url, title, company, location, description, employmentType,
                publishedAt, deadline, rawPayload, locationData, providerTenant, RawCareerData.empty());
    }
}
