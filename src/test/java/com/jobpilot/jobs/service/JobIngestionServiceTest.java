package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.RelevanceDecision;
import com.jobpilot.jobs.domain.ScreeningDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.matching.ScoreCard;
import com.jobpilot.sources.JobSource;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.ArgumentMatchers.anyInt;

import com.jobpilot.sources.SourceFetchLogHandle;
import com.jobpilot.sources.SourceFetchLogLifecycleService;
import com.jobpilot.sources.SourceFetchLogTerminalOutcome;
import com.jobpilot.telegram.TelegramNotifier;
import java.time.Clock;
import java.util.List;
import com.jobpilot.support.TestProperties;
import org.junit.jupiter.api.Test;

class JobIngestionServiceTest {
    @Test
    void oneSourceFailureDoesNotPreventTheNextSourceFromRunning() {
        JobSource failing = new StubSource("failing", true);
        JobSource succeeding = new StubSource("succeeding", false);
        JobRelevanceFilter relevance = new JobRelevanceFilter(TestProperties.create());
        JobProcessor processor = mock(JobProcessor.class);
        SourceFetchLogLifecycleService logs = lifecycle();
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        when(processor.process(any())).thenReturn(new JobProcessingResult(mock(com.jobpilot.jobs.domain.Job.class), null, false));
        var service = new JobIngestionService(List.of(failing, succeeding), relevance, processor,
                logs, telegram, Clock.systemUTC());

        service.fetchAllSources();

        verify(processor).process(any());
        verify(telegram, never()).notifyExcellent(any(), any());
        // Both sources open a row; the failing one is finalized FAILED, the other SUCCESS.
        verify(logs, org.mockito.Mockito.times(2)).begin(anyString(), any(), any());
        verify(logs).fail(any(), org.mockito.ArgumentMatchers.eq(
                com.jobpilot.sources.SourceFetchFailureCategory.SOURCE_FAILURE), any(), any());
        verify(logs).succeed(any(), anyInt(), anyInt(), any());
    }

    @Test
    void smokeReportSeparatesRawEligibleUnknownAndRejectedVacancies() {
        List<RawJob> vacancies = List.of(
                raw("1", "https://example.com/1", "Bucharest", "Java role"),
                raw("1-copy", "https://example.com/1", "Bucharest", "Duplicate"),
                raw("2", "https://example.com/2", "Remote - Europe", "Fully remote role"),
                raw("3", "https://example.com/3", "Remote", "Java role"),
                raw("4", "https://example.com/4", "Remote", "Fully remote. US only."),
                raw("5", "https://example.com/5", "Remote", "Fully remote. APAC only."),
                raw("6", "https://example.com/6", "Cluj-Napoca", "Java role"),
                raw("7", "https://example.com/7", "Berlin — Hybrid", "Java role"),
                rawWithTitle("8", "https://example.com/8", "Bucharest",
                        "Senior Java Developer", "Java role"),
                new RawJob("fixture", "9", "https://example.com/9", "Software Engineer",
                        "Example", "Remote — Europe", "Java role", null, null, null, "{}"));
        JobSource source = new JobSource() {
            public String getSourceName() { return "fixture"; }
            public List<RawJob> fetchJobs() { return vacancies; }
        };
        JobRelevanceFilter relevance = new JobRelevanceFilter(TestProperties.create());
        JobProcessor processor = mock(JobProcessor.class);
        SourceFetchLogLifecycleService logs = lifecycle();
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        when(processor.process(any(RawJob.class), any(LocationEligibilityDecision.class),
                any(EarlyCareerDecision.class), any(RelevanceDecision.class)))
                .thenAnswer(invocation -> {
                    LocationEligibilityDecision location = invocation.getArgument(1);
                    EarlyCareerDecision career = invocation.getArgument(2);
                    RelevanceDecision role = invocation.getArgument(3);
                    ScreeningDecision screening = ScreeningDecision.of(location, career, role);
                    return screening.disposition() == ScreeningDisposition.REJECT
                            ? JobProcessingResult.rejected(location, career, role, screening)
                            : new JobProcessingResult(mock(com.jobpilot.jobs.domain.Job.class), null,
                            JobPersistenceOutcome.UNCHANGED, location, career, role, screening);
                });
        stubRejectedReconciliation(processor, JobPersistenceOutcome.NOT_PERSISTED);
        var service = new JobIngestionService(List.of(source), relevance, processor,
                new LocationEligibilityService(TestProperties.create()), logs, telegram, Clock.systemUTC());

        JobIngestionReport report = service.fetchAllSources();

        assertThat(report.totalVacanciesFetched()).isEqualTo(10);
        assertThat(report.totalUniqueVacanciesBeforeEligibilityFiltering()).isEqualTo(9);
        assertThat(report.bucharestLocalVacancies()).isEqualTo(2);
        assertThat(report.remoteVacanciesEligibleFromRomania()).isEqualTo(2);
        assertThat(report.remoteEligibilityUnknown()).isEqualTo(1);
        assertThat(report.rejectedByGeographicRestriction()).isEqualTo(2);
        assertThat(report.rejectedOnsiteOrHybridOutsideBucharest()).isEqualTo(2);
        assertThat(report.earlyCareerEligibleVacancies()).isEqualTo(3);
        assertThat(report.earlyCareerEligibilityUnknown()).isEqualTo(1);
        assertThat(report.rejectedBySeniorityOrExperience()).isEqualTo(1);
        assertThat(report.locationAndCareerEligibleVacancies()).isEqualTo(4);
        assertThat(report.relevanceMatchVacancies()).isEqualTo(4);
        assertThat(report.relevanceReviewVacancies()).isZero();
        assertThat(report.rejectedByRelevance()).isZero();
        assertThat(report.finalMatchVacancies()).isEqualTo(2);
        assertThat(report.finalReviewVacancies()).isEqualTo(2);
        assertThat(report.finalMatchTenantsByProvider()).containsKey("fixture");
        assertThat(report.finalReviewTenantsByProvider()).containsKey("fixture");
        assertThat(report.finalRejectVacancies()).isEqualTo(5);
        assertThat(report.duplicateRawVacancies()).isEqualTo(1);
        assertThat(report.existingUnchangedVacancies()).isEqualTo(4);
        assertThat(report.updatedVacancies()).isZero();
        verify(telegram, never()).notifyExcellent(any(), any());
    }

    @Test
    void persistsAndScoresMatchAndReviewButNotifiesOnlyMatch() {
        List<RawJob> vacancies = List.of(
                rawForTenant("match", "Match Tenant", "Bucharest",
                        "Junior Backend Engineer", "Java, Spring Boot, PostgreSQL, REST APIs."),
                rawForTenant("review", "Review Tenant", "Remote",
                        "Software Engineering Internship",
                        "General software development using Python and cloud services."),
                rawForTenant("reject", "Reject Tenant", "Bucharest",
                        "Pre-Sales Solutions Engineer", "Java APIs and cloud integrations."));
        JobSource source = new JobSource() {
            public String getSourceName() { return "fixture"; }
            public List<RawJob> fetchJobs() { return vacancies; }
        };
        JobRelevanceFilter relevance = new JobRelevanceFilter(TestProperties.create());
        JobProcessor processor = mock(JobProcessor.class);
        SourceFetchLogLifecycleService logs = lifecycle();
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        when(processor.process(any(RawJob.class), any(LocationEligibilityDecision.class),
                any(EarlyCareerDecision.class), any(RelevanceDecision.class)))
                .thenAnswer(invocation -> {
                    LocationEligibilityDecision location = invocation.getArgument(1);
                    EarlyCareerDecision career = invocation.getArgument(2);
                    RelevanceDecision role = invocation.getArgument(3);
                    ScreeningDecision screening = ScreeningDecision.of(location, career, role);
                    if (screening.disposition() == ScreeningDisposition.REJECT) {
                        return JobProcessingResult.rejected(location, career, role, screening);
                    }
                    return new JobProcessingResult(mock(com.jobpilot.jobs.domain.Job.class),
                            excellent(), JobPersistenceOutcome.CREATED,
                            location, career, role, screening);
                });
        stubRejectedReconciliation(processor, JobPersistenceOutcome.NOT_PERSISTED);
        var service = new JobIngestionService(List.of(source), relevance, processor,
                new LocationEligibilityService(TestProperties.create()),
                new EarlyCareerEligibilityService(), logs, telegram, Clock.systemUTC());

        JobIngestionReport report = service.fetchAllSources();

        assertThat(report.finalMatchVacancies()).isEqualTo(1);
        assertThat(report.finalReviewVacancies()).isEqualTo(1);
        assertThat(report.rejectedByRelevance()).isEqualTo(1);
        assertThat(report.persistedNewVacancies()).isEqualTo(2);
        assertThat(report.finalMatchTenantsByProvider().get("fixture"))
                .containsExactly("Match Tenant");
        assertThat(report.finalReviewTenantsByProvider().get("fixture"))
                .containsExactly("Review Tenant");
        verify(telegram, times(1)).notifyExcellent(any(), any());
    }

    @Test
    void rawDuplicatesUnchangedRowsAndUpdatedRowsUseIndependentCounters() {
        RawJob unchanged = raw("unchanged", "https://example.com/unchanged",
                "Bucharest", "Java internship");
        RawJob rawDuplicate = raw("duplicate-copy", "https://example.com/unchanged",
                "Bucharest", "same raw identity");
        RawJob updatedReview = raw("updated", "https://example.com/updated",
                "Remote", "Java internship");
        JobProcessor processor = mock(JobProcessor.class);
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        when(processor.process(any(RawJob.class), any(LocationEligibilityDecision.class),
                any(EarlyCareerDecision.class), any(RelevanceDecision.class)))
                .thenAnswer(invocation -> {
                    RawJob raw = invocation.getArgument(0);
                    LocationEligibilityDecision location = invocation.getArgument(1);
                    EarlyCareerDecision career = invocation.getArgument(2);
                    RelevanceDecision role = invocation.getArgument(3);
                    ScreeningDecision screening = ScreeningDecision.of(location, career, role);
                    JobPersistenceOutcome outcome = raw.externalId().equals("updated")
                            ? JobPersistenceOutcome.UPDATED : JobPersistenceOutcome.UNCHANGED;
                    return new JobProcessingResult(mock(com.jobpilot.jobs.domain.Job.class),
                            excellent(), outcome, location, career, role, screening);
                });

        JobIngestionReport report = new JobIngestionService(
                List.of(new JobSource() {
                    public String getSourceName() { return "fixture"; }
                    public List<RawJob> fetchJobs() {
                        return List.of(unchanged, rawDuplicate, updatedReview);
                    }
                }), new JobRelevanceFilter(TestProperties.create()), processor,
                new LocationEligibilityService(TestProperties.create()),
                new EarlyCareerEligibilityService(), successfulLogs(), telegram,
                Clock.systemUTC()).fetchAllSources();

        assertThat(report.duplicateRawVacancies()).isEqualTo(1);
        assertThat(report.existingUnchangedVacancies()).isEqualTo(1);
        assertThat(report.updatedVacancies()).isEqualTo(1);
        assertThat(report.persistedNewVacancies()).isZero();
        verify(telegram, never()).notifyExcellent(any(), any());
    }

    @Test
    void newLocationRejectSkipsLaterStagesAndOnlyAttemptsReconciliation() {
        RawJob raw = raw("location-reject", "https://example.com/location-reject",
                "Cluj-Napoca", "Java role");
        LocationEligibilityService location = mock(LocationEligibilityService.class);
        EarlyCareerEligibilityService career = mock(EarlyCareerEligibilityService.class);
        JobRelevanceFilter relevance = mock(JobRelevanceFilter.class);
        JobProcessor processor = mock(JobProcessor.class);
        SourceFetchLogLifecycleService logs = successfulLogs();
        LocationEligibilityDecision rejection =
                new LocationEligibilityService(TestProperties.create()).evaluate(raw);
        when(location.evaluate(raw)).thenReturn(rejection);
        stubRejectedReconciliation(processor, JobPersistenceOutcome.NOT_PERSISTED);

        JobIngestionReport report = new JobIngestionService(List.of(source(raw)), relevance,
                processor, location, career, logs, mock(TelegramNotifier.class), Clock.systemUTC())
                .fetchAllSources();

        assertThat(report.finalRejectVacancies()).isEqualTo(1);
        assertThat(report.persistedNewVacancies()).isZero();
        assertThat(report.updatedVacancies()).isZero();
        assertThat(report.existingUnchangedVacancies()).isZero();
        verify(career, never()).evaluate(any());
        verify(relevance, never()).evaluate(any());
        verify(processor, never()).process(any(RawJob.class), any(LocationEligibilityDecision.class),
                any(EarlyCareerDecision.class), any(RelevanceDecision.class));
        verify(processor).reconcileRejected(raw, rejection, null, null);
    }

    @Test
    void existingMatchBecomingCareerRejectSkipsRelevanceAndCountsAnUpdate() {
        RawJob raw = rawWithTitle("career-reject", "https://example.com/career-reject",
                "Bucharest", "Senior Java Developer", "Java role");
        LocationEligibilityService location = mock(LocationEligibilityService.class);
        EarlyCareerEligibilityService career = mock(EarlyCareerEligibilityService.class);
        JobRelevanceFilter relevance = mock(JobRelevanceFilter.class);
        JobProcessor processor = mock(JobProcessor.class);
        LocationEligibilityDecision locationDecision =
                new LocationEligibilityService(TestProperties.create()).evaluate(raw);
        EarlyCareerDecision careerDecision = new EarlyCareerEligibilityService().evaluate(raw);
        when(location.evaluate(raw)).thenReturn(locationDecision);
        when(career.evaluate(raw)).thenReturn(careerDecision);
        stubRejectedReconciliation(processor, JobPersistenceOutcome.UPDATED);
        TelegramNotifier telegram = mock(TelegramNotifier.class);

        JobIngestionReport report = new JobIngestionService(List.of(source(raw)), relevance,
                processor, location, career, successfulLogs(), telegram,
                Clock.systemUTC()).fetchAllSources();

        assertThat(report.finalRejectVacancies()).isEqualTo(1);
        assertThat(report.updatedVacancies()).isEqualTo(1);
        assertThat(report.persistedNewVacancies()).isZero();
        verify(relevance, never()).evaluate(any());
        verify(processor, never()).process(any(RawJob.class), any(LocationEligibilityDecision.class),
                any(EarlyCareerDecision.class), any(RelevanceDecision.class));
        verify(processor).reconcileRejected(raw, locationDecision, careerDecision, null);
        verify(telegram, never()).notifyExcellent(any(), any());
    }

    @Test
    void existingReviewBecomingRelevanceRejectOnlyAttemptsReconciliation() {
        RawJob raw = rawWithTitle("role-reject", "https://example.com/role-reject",
                "Bucharest", "Pre-Sales Solutions Engineer", "Java integrations");
        LocationEligibilityService location = mock(LocationEligibilityService.class);
        EarlyCareerEligibilityService career = mock(EarlyCareerEligibilityService.class);
        JobRelevanceFilter relevance = mock(JobRelevanceFilter.class);
        JobProcessor processor = mock(JobProcessor.class);
        LocationEligibilityDecision locationDecision =
                new LocationEligibilityService(TestProperties.create()).evaluate(raw);
        EarlyCareerDecision careerDecision = new EarlyCareerEligibilityService().evaluate(raw);
        RelevanceDecision relevanceDecision =
                new JobRelevanceFilter(TestProperties.create()).evaluate(raw);
        when(location.evaluate(raw)).thenReturn(locationDecision);
        when(career.evaluate(raw)).thenReturn(careerDecision);
        when(relevance.evaluate(raw)).thenReturn(relevanceDecision);
        stubRejectedReconciliation(processor, JobPersistenceOutcome.UPDATED);

        JobIngestionReport report = new JobIngestionService(List.of(source(raw)), relevance,
                processor, location, career, successfulLogs(), mock(TelegramNotifier.class),
                Clock.systemUTC()).fetchAllSources();

        assertThat(report.rejectedByRelevance()).isEqualTo(1);
        assertThat(report.finalRejectVacancies()).isEqualTo(1);
        assertThat(report.updatedVacancies()).isEqualTo(1);
        verify(processor, never()).process(any(RawJob.class), any(LocationEligibilityDecision.class),
                any(EarlyCareerDecision.class), any(RelevanceDecision.class));
        verify(processor).reconcileRejected(
                raw, locationDecision, careerDecision, relevanceDecision);
    }

    @Test
    void duplicateRejectedRawIdentityReconcilesAndCountsOnlyOnce() {
        RawJob first = raw("reject", "https://example.com/duplicate-reject",
                "USA | Remote", "Java role");
        RawJob duplicate = raw("reject-copy", "https://example.com/duplicate-reject",
                "USA | Remote", "duplicate payload");
        JobProcessor processor = mock(JobProcessor.class);
        stubRejectedReconciliation(processor, JobPersistenceOutcome.UPDATED);

        JobIngestionReport report = new JobIngestionService(
                List.of(new JobSource() {
                    public String getSourceName() { return "fixture"; }
                    public List<RawJob> fetchJobs() { return List.of(first, duplicate); }
                }), mock(JobRelevanceFilter.class), processor,
                new LocationEligibilityService(TestProperties.create()),
                mock(EarlyCareerEligibilityService.class), successfulLogs(),
                mock(TelegramNotifier.class), Clock.systemUTC()).fetchAllSources();

        assertThat(report.totalVacanciesFetched()).isEqualTo(2);
        assertThat(report.totalUniqueVacanciesBeforeEligibilityFiltering()).isEqualTo(1);
        assertThat(report.duplicateRawVacancies()).isEqualTo(1);
        assertThat(report.finalRejectVacancies()).isEqualTo(1);
        assertThat(report.updatedVacancies()).isEqualTo(1);
        assertThat(report.existingUnchangedVacancies()).isZero();
        assertThat(report.persistedNewVacancies()).isZero();
        verify(processor, times(1)).reconcileRejected(
                any(RawJob.class), any(LocationEligibilityDecision.class), any(), any());
    }

    private RawJob raw(String id, String url, String location, String description) {
        return rawWithTitle(id, url, location, "Java Developer Intern", description);
    }

    private RawJob rawWithTitle(String id, String url, String location, String title,
                                String description) {
        return new RawJob("fixture", id, url, title, "Example",
                location, description, "Internship", null, null, "{}");
    }

    private RawJob rawForTenant(String id, String tenant, String location,
                                String title, String description) {
        return new RawJob("fixture", id, "https://example.com/" + id, title, tenant,
                location, description, "Internship", null, null, "{}",
                com.jobpilot.jobs.domain.RawLocationData.empty(), tenant);
    }

    private ScoreCard excellent() {
        return new ScoreCard(90, ScoreBand.EXCELLENT_MATCH, true,
                10, 25, 15, 10, 10, 10, 10, 0,
                List.of("match"), List.of(), List.of());
    }

    private JobSource source(RawJob raw) {
        return new JobSource() {
            public String getSourceName() { return "fixture"; }
            public List<RawJob> fetchJobs() { return List.of(raw); }
        };
    }

    private SourceFetchLogLifecycleService successfulLogs() {
        SourceFetchLogLifecycleService logs = lifecycle();
        return logs;
    }

    private void stubRejectedReconciliation(JobProcessor processor,
                                            JobPersistenceOutcome outcome) {
        when(processor.reconcileRejected(any(RawJob.class),
                any(LocationEligibilityDecision.class), any(), any()))
                .thenAnswer(invocation -> {
                    LocationEligibilityDecision location = invocation.getArgument(1);
                    EarlyCareerDecision career = invocation.getArgument(2);
                    RelevanceDecision role = invocation.getArgument(3);
                    ScreeningDecision screening = ScreeningDecision.of(location, career, role);
                    com.jobpilot.jobs.domain.Job job = outcome == JobPersistenceOutcome.NOT_PERSISTED
                            ? null : mock(com.jobpilot.jobs.domain.Job.class);
                    return new JobProcessingResult(job, null, outcome,
                            location, career, role, screening);
                });
    }

    private static final class StubSource implements JobSource {
        private final String name;
        private final boolean fail;

        private StubSource(String name, boolean fail) {
            this.name = name;
            this.fail = fail;
        }

        @Override
        public String getSourceName() { return name; }

        @Override
        public List<RawJob> fetchJobs() {
            if (fail) throw new IllegalStateException("temporary source failure");
            return List.of(new RawJob(name, "1", "https://example.com/jobs/1", "Java Developer Intern",
                    "Example", "Romania", "Java Internship", "Internship", null, null, "{}"));
        }
    }

    /** A lifecycle mock that hands out handles and reports every terminal write as done. */
    private static SourceFetchLogLifecycleService lifecycle() {
        SourceFetchLogLifecycleService lifecycle = mock(SourceFetchLogLifecycleService.class);
        java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong();
        when(lifecycle.begin(anyString(), any(), any())).thenAnswer(invocation ->
                new SourceFetchLogHandle(ids.incrementAndGet(), invocation.getArgument(0),
                        invocation.getArgument(1)));
        when(lifecycle.succeed(any(), anyInt(), anyInt(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.UPDATED);
        when(lifecycle.fail(any(), any(), any(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.UPDATED);
        return lifecycle;
    }

}
