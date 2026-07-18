package com.jobpilot.sources.greenhouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.support.TestProperties;
import org.junit.jupiter.api.Test;

class GreenhouseJobSourceTest {
    @Test
    void parsesPublicBoardPayload() throws Exception {
        var source = new GreenhouseJobSource(mock(ExternalHttpClient.class), TestProperties.create());
        var json = new ObjectMapper().readTree("""
                {"jobs":[{"id":123,"title":"Java Intern","absolute_url":"https://boards.example/jobs/123",
                "location":{"name":"Bucharest"},"content":"<p>Java &amp; Spring Boot</p>",
                "updated_at":"2026-07-16T10:00:00Z"}]}
                """);

        var jobs = source.parse("acme", json);

        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.externalId()).isEqualTo("123");
            assertThat(job.company()).isEqualTo("acme");
            assertThat(job.description()).isEqualTo("Java & Spring Boot");
            assertThat(job.publishedAt()).isNotNull();
        });
    }
}
