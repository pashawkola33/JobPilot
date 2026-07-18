package com.jobpilot.manualurl.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.sources.greenhouse.GreenhouseJobSource;
import com.jobpilot.sources.lever.LeverJobSource;
import com.jobpilot.support.TestProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;

class ManualAtsResolverTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExternalHttpClient http = mock(ExternalHttpClient.class);
    private final ManualAtsResolver resolver = new ManualAtsResolver(http,
            new GreenhouseJobSource(http, TestProperties.create()),
            new LeverJobSource(http, TestProperties.create()));

    @Test
    void reusesGreenhouseApiForRecognizedUrl() throws Exception {
        String api = "https://boards-api.greenhouse.io/v1/boards/acme/jobs/123?content=true";
        when(http.getJson(api)).thenReturn(objectMapper.readTree("""
                {"id":123,"absolute_url":"https://job-boards.greenhouse.io/acme/jobs/123",
                 "title":"Java Intern","content":"<p>Java internship with mentorship and Spring Boot.</p>",
                 "location":{"name":"Bucharest, Romania"},"updated_at":"2026-07-18T10:00:00Z"}
                """));

        var result = resolver.fetch(URI.create("https://job-boards.greenhouse.io/acme/jobs/123"));

        assertThat(result).hasValueSatisfying(job -> {
            assertThat(job.source()).isEqualTo("greenhouse");
            assertThat(job.externalId()).isEqualTo("123");
        });
        verify(http).getJson(api);
    }

    @Test
    void reusesLeverApiForRecognizedUrl() throws Exception {
        String api = "https://api.lever.co/v0/postings/acme/abc-123";
        when(http.getJson(api)).thenReturn(objectMapper.readTree("""
                {"id":"abc-123","hostedUrl":"https://jobs.lever.co/acme/abc-123",
                 "text":"Backend Developer Intern",
                 "descriptionPlain":"Java internship with Spring Boot, SQL, testing and mentorship.",
                 "categories":{"location":"Bucharest, Romania","commitment":"Internship"},
                 "createdAt":1784368800000}
                """));

        var result = resolver.fetch(URI.create("https://jobs.lever.co/acme/abc-123"));

        assertThat(result).hasValueSatisfying(job -> {
            assertThat(job.source()).isEqualTo("lever");
            assertThat(job.externalId()).isEqualTo("abc-123");
        });
        verify(http).getJson(api);
    }
}
