package com.jobpilot.sources.ashby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.support.TestProperties;
import org.junit.jupiter.api.Test;

class AshbyJobSourceTest {
    @Test
    void mapsStructuredWorkplaceAndLocationsWithoutDecidingEligibility() throws Exception {
        var source = new AshbyJobSource(mock(ExternalHttpClient.class), TestProperties.create());
        var json = new ObjectMapper().readTree("""
                {"jobs":[{"id":"abc","title":"Java Engineer","jobUrl":"https://jobs.example/abc",
                "descriptionHtml":"<p>Java &amp; Spring</p>","employmentType":"FullTime",
                "location":"Remote - European Union","workplaceType":"Remote","isListed":true,
                "secondaryLocations":[{"location":"Romania"}],"publishedAt":"2026-07-20T10:00:00Z"}]}
                """);

        var jobs = source.parse("example", json);

        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.description()).isEqualTo("Java & Spring");
            assertThat(job.locationData().workplaceType()).isEqualTo("Remote");
            assertThat(job.locationData().structuredLocations())
                    .containsExactly("Remote - European Union", "Romania");
        });
    }
}
