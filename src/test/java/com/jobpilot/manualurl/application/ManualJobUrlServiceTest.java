package com.jobpilot.manualurl.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RemoteType;
import com.jobpilot.jobs.service.JobProcessingResult;
import com.jobpilot.manualurl.domain.ManualJobStatus;
import com.jobpilot.manualurl.fetch.ManualAtsResolver;
import com.jobpilot.manualurl.fetch.ManualFetchedResource;
import com.jobpilot.manualurl.fetch.ManualUrlPolicy;
import com.jobpilot.manualurl.fetch.ManualUrlValidationException;
import com.jobpilot.manualurl.fetch.SafeManualPageFetcher;
import com.jobpilot.manualurl.fetch.ValidatedManualUrl;
import com.jobpilot.manualurl.parse.DeterministicManualJobParser;
import com.jobpilot.manualurl.parse.ManualParseResult;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCard;
import com.jobpilot.support.TestProperties;
import java.net.InetAddress;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManualJobUrlServiceTest {
    private final ManualUrlPolicy policy = mock(ManualUrlPolicy.class);
    private final ManualAtsResolver ats = mock(ManualAtsResolver.class);
    private final SafeManualPageFetcher fetcher = mock(SafeManualPageFetcher.class);
    private final DeterministicManualJobParser parser = mock(DeterministicManualJobParser.class);
    private final ManualJobPersistenceService persistence = mock(ManualJobPersistenceService.class);
    private final ManualJobUrlService service = new ManualJobUrlService(
            policy, ats, fetcher, parser, persistence, TestProperties.create());

    private ValidatedManualUrl validated;

    @BeforeEach
    void setUp() throws Exception {
        validated = new ValidatedManualUrl(URI.create("https://public.example/jobs/42"),
                List.of(InetAddress.getByName("93.184.216.34")));
        when(policy.validate(any())).thenReturn(validated);
    }

    @Test
    void recognizedAtsJobBypassesGenericPageFetcher() {
        RawJob raw = rawJob();
        when(ats.fetch(validated.uri())).thenReturn(Optional.of(raw));
        when(persistence.persist(raw)).thenReturn(processed(false));

        var result = service.submit(validated.uri().toString());

        assertThat(result.status()).isEqualTo(ManualJobStatus.ALREADY_EXISTS);
        verify(fetcher, never()).fetch(any());
    }

    @Test
    void parsedPublicPageCreatesJobThroughExistingProcessorBoundary() {
        var fetched = new ManualFetchedResource(validated.uri(), validated.uri(),
                "text/html", "fixture");
        var vacancy = new com.jobpilot.manualurl.parse.ParsedManualVacancy(
                "manual", "42", validated.uri().toString(), "Java Intern", "Example",
                "Bucharest, Romania", rawJob().description(), "Internship", null, null,
                com.jobpilot.manualurl.domain.ManualSourceClassification.SCHEMA_ORG_JOB_POSTING);
        when(ats.fetch(validated.uri())).thenReturn(Optional.empty());
        when(fetcher.fetch(validated)).thenReturn(fetched);
        when(parser.parse(fetched)).thenReturn(ManualParseResult.success(vacancy));
        when(persistence.persist(any())).thenReturn(processed(true));

        var result = service.submit(validated.uri().toString());

        assertThat(result.status()).isEqualTo(ManualJobStatus.CREATED);
        assertThat(result.score()).isEqualTo(80);
        assertThat(result.strengths()).containsExactly("Java match");
        verify(persistence).persist(any(RawJob.class));
    }

    @Test
    void invalidUrlReturnsTypedFailureWithoutFetching() {
        when(policy.validate(any())).thenThrow(new ManualUrlValidationException(
                ManualUrlValidationException.Reason.PROHIBITED_DESTINATION, "blocked"));

        var result = service.submit("http://localhost/job");

        assertThat(result.status()).isEqualTo(ManualJobStatus.INVALID_URL);
        assertThat(result.message()).doesNotContain("localhost");
        verify(ats, never()).fetch(any());
    }

    private RawJob rawJob() {
        return new RawJob("manual", "42", validated.uri().toString(), "Java Intern", "Example",
                "Bucharest, Romania", "Java internship with Spring Boot, SQL, testing and mentorship.",
                "Internship", null, null, "fixture");
    }

    private JobProcessingResult processed(boolean created) {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        Job job = new Job("manual", "42", validated.uri().toString(), "Java Intern", "Example",
                "Bucharest, Romania", RemoteType.ONSITE, "Internship", rawJob().description(),
                null, null, "a".repeat(64), "b".repeat(64), "c".repeat(64), now);
        ScoreCard score = new ScoreCard(80, ScoreBand.GOOD_MATCH, true,
                20, 20, 10, 5, 10, 10, 5, 0,
                List.of("Java match"), List.of("Confirm schedule"), List.of());
        return new JobProcessingResult(job, score, created);
    }
}
