package com.jobpilot.browser.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed worker response. Only the validated canonical fields are consumed; any
 * unknown property (HTML, cookies, headers, paths, fingerprints) is ignored, and
 * Node never supplies a database identifier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrowserExtractionResponse(
        BrowserExtractionStatus status,
        String finalUrl,
        String sourceType,
        Job job,
        Evidence evidence) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Job(
            String title,
            String company,
            String location,
            String description,
            String employmentType,
            String publishedAt,
            String canonicalUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evidence(String titleSource, String companySource, String descriptionSource) {
    }

    public static BrowserExtractionResponse unavailable() {
        return new BrowserExtractionResponse(BrowserExtractionStatus.WORKER_UNAVAILABLE, null, null, null, null);
    }
}
