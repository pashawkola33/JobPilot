package com.jobpilot.sources.recruitee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.support.TestProperties;
import org.junit.jupiter.api.Test;

class RecruiteeJobSourceTest {
    @Test
    void mapsPublicOfferWorkplaceAndMultipleLocationsWithoutDecidingEligibility() throws Exception {
        var source = new RecruiteeJobSource(mock(ExternalHttpClient.class), TestProperties.create());
        var json = new ObjectMapper().readTree("""
                {"offers":[{"id":42,"guid":"stable-guid-42","title":"Java Engineer","company_name":"Example",
                "careers_url":"https://example.recruitee.com/o/java","description":"<p>Java</p>",
                "requirements":"<p>Spring Boot</p>","employment_type_code":"full_time",
                "experience_code":"no_experience",
                "location":"Bucharest, Romania","remote":false,"hybrid":true,"on_site":true,
                "locations":[{"city":"Bucharest","state":"București","country":"Romania"}],
                "published_at":"2026-07-20T10:00:00Z"}]}
                """);

        var jobs = source.parse("example", json);

        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.externalId()).isEqualTo("stable-guid-42");
            assertThat(job.description()).isEqualTo("Java Spring Boot");
            assertThat(job.locationData().workplaceType()).isEqualTo("Hybrid");
            assertThat(job.locationData().structuredLocations())
                    .anyMatch(location -> location.contains("Bucharest"));
            assertThat(job.careerData().maximumYears()).isEqualTo(0.0);
        });
    }
}
