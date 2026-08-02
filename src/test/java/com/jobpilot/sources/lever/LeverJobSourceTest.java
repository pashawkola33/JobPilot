package com.jobpilot.sources.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.support.TestProperties;
import org.junit.jupiter.api.Test;

class LeverJobSourceTest {
    @Test
    void parsesPublicPostingPayload() throws Exception {
        var source = new LeverJobSource(mock(ExternalHttpClient.class), TestProperties.create());
        var json = new ObjectMapper().readTree("""
                [{"id":"abc","text":"Software Engineer Intern","hostedUrl":"https://jobs.example/abc",
                "description":"<p>Java &amp; REST</p>","createdAt":1784200000000,
                "lists":[{"text":"Requirements","content":"<li>No experience required</li>"}],
                "categories":{"location":"Romania","commitment":"Internship","level":"Entry Level"}}]
                """);

        var jobs = source.parse("acme", json);

        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.externalId()).isEqualTo("abc");
            assertThat(job.location()).isEqualTo("Romania");
            assertThat(job.description()).isEqualTo("Java & REST Requirements No experience required");
            assertThat(job.employmentType()).isEqualTo("Internship");
            assertThat(job.careerData().providerSeniority()).isEqualTo("Entry Level");
        });
    }
}
