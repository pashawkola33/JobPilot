package com.jobpilot.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.browser.api.BrowserExtractionResponse;
import com.jobpilot.browser.api.BrowserExtractionStatus;
import com.jobpilot.browser.config.ScraperWorkerProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.service.EarlyCareerEligibilityService;
import com.jobpilot.jobs.service.JobIngestionReport;
import com.jobpilot.jobs.service.JobIngestionService;
import com.jobpilot.jobs.service.JobProcessingResult;
import com.jobpilot.jobs.service.JobProcessor;
import com.jobpilot.jobs.service.JobRelevanceFilter;
import com.jobpilot.jobs.service.LocationEligibilityService;
import com.jobpilot.manualurl.fetch.ValidatedManualUrl;
import com.jobpilot.manualurl.parse.ManualParseStatus;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchLogRepository;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.TelegramNotifier;
import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserToJavaEligibilityPipelineTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final ValidatedManualUrl VALIDATED = validated();

    @Test
    void rejectsOnsiteNewYorkBeforeScoringPersistenceOrNotification() {
        Pipeline result = run(job("Software Engineer", "New York City, United States",
                "Onsite software engineering role building Java services with a product team.", "Full time"));

        assertRejected(result, 1, 0);
    }

    @Test
    void rejectsUnitedStatesRestrictedRemoteBeforeScoringPersistenceOrNotification() {
        Pipeline result = run(job("Staff Software Engineer", "Remote",
                "Fully remote role. Candidates must be based in the United States.", "Full time"));

        assertRejected(result, 1, 0);
    }

    @Test
    void rejectsUnknownRemoteScopeBeforeScoringPersistenceOrNotification() {
        Pipeline result = run(job("Software Engineer", "Remote",
                "Work remotely building Java and Spring Boot services with the engineering team.", "Full time"));

        assertThat(result.report().remoteEligibilityUnknown()).isEqualTo(1);
        assertRejected(result, 0, 0);
    }

    @Test
    void rejectsJuniorRoleWithThreeMandatoryYearsBeforeScoringPersistenceOrNotification() {
        Pipeline result = run(job("Junior Software Engineer", "Bucharest, Romania",
                "Junior Java role in Bucharest. At least 3+ years of professional experience is required.",
                "Full time"));

        assertRejected(result, 0, 1);
    }

    @Test
    void acceptsBucharestInternshipIntoTheCentralProcessor() {
        Pipeline result = run(job("Software Engineering Intern", "Bucharest, Romania",
                "Java and Spring Boot internship with mentoring, tests, and production delivery.",
                "Internship"));

        assertThat(result.report().finalUniqueEligibleVacancies()).isEqualTo(1);
        verify(result.processor()).process(any(RawJob.class),
                any(com.jobpilot.jobs.domain.LocationEligibilityDecision.class),
                any(com.jobpilot.jobs.domain.EarlyCareerDecision.class));
        verify(result.telegram(), never()).notifyExcellent(any(), any());
    }

    private Pipeline run(BrowserExtractionResponse response) {
        ScraperWorkerProperties settings = new ScraperWorkerProperties(true,
                "http://scraper-worker:3000", SECRET, Duration.ofSeconds(5), Duration.ofSeconds(45),
                1_048_576, 50_000);
        BrowserFallbackService fallback = new BrowserFallbackService(settings, request -> response);
        RawJob raw = fallback.attempt(VALIDATED, ManualParseStatus.JS_RENDERING_REQUIRED).orElseThrow();
        JobSource source = new JobSource() {
            @Override
            public String getSourceName() { return "browser"; }

            @Override
            public List<RawJob> fetchJobs() { return List.of(raw); }
        };
        JobProcessor processor = mock(JobProcessor.class);
        when(processor.process(any(RawJob.class),
                any(com.jobpilot.jobs.domain.LocationEligibilityDecision.class),
                any(com.jobpilot.jobs.domain.EarlyCareerDecision.class)))
                .thenReturn(new JobProcessingResult(mock(com.jobpilot.jobs.domain.Job.class), null, false));
        JobRelevanceFilter relevance = mock(JobRelevanceFilter.class);
        when(relevance.isRelevant(any())).thenReturn(true);
        SourceFetchLogRepository logs = mock(SourceFetchLogRepository.class);
        when(logs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        var properties = TestProperties.create();
        JobIngestionService service = new JobIngestionService(List.of(source), relevance, processor,
                new LocationEligibilityService(properties), new EarlyCareerEligibilityService(),
                logs, telegram, Clock.systemUTC());
        return new Pipeline(service.fetchAllSources(), processor, telegram);
    }

    private void assertRejected(Pipeline result, int locationRejected, int careerRejected) {
        assertThat(result.report().rejectedOnsiteOrHybridOutsideBucharest()
                + result.report().rejectedByGeographicRestriction()).isEqualTo(locationRejected);
        assertThat(result.report().rejectedBySeniorityOrExperience()).isEqualTo(careerRejected);
        verify(result.processor(), never()).process(any(RawJob.class),
                any(com.jobpilot.jobs.domain.LocationEligibilityDecision.class),
                any(com.jobpilot.jobs.domain.EarlyCareerDecision.class));
        verify(result.telegram(), never()).notifyExcellent(any(), any());
    }

    private BrowserExtractionResponse job(String title, String location,
                                           String description, String employmentType) {
        return new BrowserExtractionResponse(BrowserExtractionStatus.EXTRACTED,
                VALIDATED.uri().toString(), "BROWSER",
                new BrowserExtractionResponse.Job(title, "Example Company", location, description,
                        employmentType, "2026-07-20", VALIDATED.uri().toString()),
                new BrowserExtractionResponse.Evidence("DOM", "DOM", "DOM"));
    }

    private static ValidatedManualUrl validated() {
        try {
            return new ValidatedManualUrl(URI.create("https://93.184.216.34/jobs/browser-42"),
                    List.of(InetAddress.getByName("93.184.216.34")));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Pipeline(JobIngestionReport report, JobProcessor processor, TelegramNotifier telegram) {
    }
}
