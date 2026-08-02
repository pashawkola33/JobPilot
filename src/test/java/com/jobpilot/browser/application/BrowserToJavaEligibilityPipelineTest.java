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
import com.jobpilot.jobs.domain.ScreeningDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
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
    void reviewsUnknownRemoteScopeAndStillUsesTheProcessorWithoutNotification() {
        Pipeline result = run(job("Software Engineer", "Remote",
                "Work remotely building Java and Spring Boot services with the engineering team.", "Full time"));

        assertThat(result.report().remoteEligibilityUnknown()).isEqualTo(1);
        assertThat(result.report().finalReviewVacancies()).isEqualTo(1);
        verifyProcessed(result);
        verify(result.telegram(), never()).notifyExcellent(any(), any());
    }

    @Test
    void reviewsJuniorRoleWithThreeMandatoryYearsWithoutNotification() {
        Pipeline result = run(job("Junior Software Engineer", "Bucharest, Romania",
                "Junior Java role in Bucharest. At least 3+ years of professional experience is required.",
                "Full time"));

        assertThat(result.report().earlyCareerEligibilityUnknown()).isEqualTo(1);
        assertThat(result.report().finalReviewVacancies()).isEqualTo(1);
        verifyProcessed(result);
        verify(result.telegram(), never()).notifyExcellent(any(), any());
    }

    @Test
    void acceptsBucharestInternshipIntoTheCentralProcessor() {
        Pipeline result = run(job("Software Engineering Intern", "Bucharest, Romania",
                "Java and Spring Boot internship with mentoring, tests, and production delivery.",
                "Internship"));

        assertThat(result.report().finalMatchVacancies()).isEqualTo(1);
        verifyProcessed(result);
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
                any(com.jobpilot.jobs.domain.EarlyCareerDecision.class),
                any(com.jobpilot.jobs.domain.RelevanceDecision.class)))
                .thenAnswer(invocation -> {
                    var location = invocation.getArgument(1,
                            com.jobpilot.jobs.domain.LocationEligibilityDecision.class);
                    var career = invocation.getArgument(2,
                            com.jobpilot.jobs.domain.EarlyCareerDecision.class);
                    var relevanceDecision = invocation.getArgument(3,
                            com.jobpilot.jobs.domain.RelevanceDecision.class);
                    ScreeningDecision screening = ScreeningDecision.of(
                            location, career, relevanceDecision);
                    if (screening.disposition() == ScreeningDisposition.REJECT) {
                        return JobProcessingResult.rejected(location, career,
                                relevanceDecision, screening);
                    }
                    return new JobProcessingResult(mock(com.jobpilot.jobs.domain.Job.class), null,
                            com.jobpilot.jobs.service.JobPersistenceOutcome.UNCHANGED,
                            location, career, relevanceDecision, screening);
                });
        when(processor.reconcileRejected(any(RawJob.class),
                any(com.jobpilot.jobs.domain.LocationEligibilityDecision.class), any(), any()))
                .thenAnswer(invocation -> {
                    var location = invocation.getArgument(1,
                            com.jobpilot.jobs.domain.LocationEligibilityDecision.class);
                    var career = invocation.getArgument(2,
                            com.jobpilot.jobs.domain.EarlyCareerDecision.class);
                    var relevanceDecision = invocation.getArgument(3,
                            com.jobpilot.jobs.domain.RelevanceDecision.class);
                    return new JobProcessingResult(null, null,
                            com.jobpilot.jobs.service.JobPersistenceOutcome.NOT_PERSISTED,
                            location, career, relevanceDecision,
                            ScreeningDecision.of(location, career, relevanceDecision));
                });
        JobRelevanceFilter relevance = new JobRelevanceFilter(TestProperties.create());
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
        assertThat(result.report().finalRejectVacancies()).isEqualTo(1);
        verify(result.processor(), never()).process(any(RawJob.class),
                any(com.jobpilot.jobs.domain.LocationEligibilityDecision.class),
                any(com.jobpilot.jobs.domain.EarlyCareerDecision.class),
                any(com.jobpilot.jobs.domain.RelevanceDecision.class));
        verify(result.processor()).reconcileRejected(any(RawJob.class),
                any(com.jobpilot.jobs.domain.LocationEligibilityDecision.class), any(), any());
        verify(result.telegram(), never()).notifyExcellent(any(), any());
    }

    private void verifyProcessed(Pipeline result) {
        verify(result.processor()).process(any(RawJob.class),
                any(com.jobpilot.jobs.domain.LocationEligibilityDecision.class),
                any(com.jobpilot.jobs.domain.EarlyCareerDecision.class),
                any(com.jobpilot.jobs.domain.RelevanceDecision.class));
        verify(result.processor(), never()).reconcileRejected(any(RawJob.class),
                any(com.jobpilot.jobs.domain.LocationEligibilityDecision.class), any(), any());
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
