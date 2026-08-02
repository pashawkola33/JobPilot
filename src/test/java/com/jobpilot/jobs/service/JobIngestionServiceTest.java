package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchLogRepository;
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
        JobRelevanceFilter relevance = mock(JobRelevanceFilter.class);
        JobProcessor processor = mock(JobProcessor.class);
        SourceFetchLogRepository logs = mock(SourceFetchLogRepository.class);
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        when(logs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relevance.isRelevant(any())).thenReturn(true);
        when(processor.process(any())).thenReturn(new JobProcessingResult(mock(com.jobpilot.jobs.domain.Job.class), null, false));
        var service = new JobIngestionService(List.of(failing, succeeding), relevance, processor,
                logs, telegram, Clock.systemUTC());

        service.fetchAllSources();

        verify(processor).process(any());
        verify(telegram, never()).notifyExcellent(any(), any());
        verify(logs, org.mockito.Mockito.times(4)).save(any());
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
        JobRelevanceFilter relevance = mock(JobRelevanceFilter.class);
        JobProcessor processor = mock(JobProcessor.class);
        SourceFetchLogRepository logs = mock(SourceFetchLogRepository.class);
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        when(logs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(relevance.isRelevant(any())).thenReturn(true);
        when(processor.process(any(RawJob.class), any(LocationEligibilityDecision.class),
                any(EarlyCareerDecision.class)))
                .thenReturn(new JobProcessingResult(mock(com.jobpilot.jobs.domain.Job.class), null, false));
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
        assertThat(report.earlyCareerEligibleVacancies()).isEqualTo(2);
        assertThat(report.earlyCareerEligibilityUnknown()).isEqualTo(1);
        assertThat(report.rejectedBySeniorityOrExperience()).isEqualTo(1);
        assertThat(report.finalUniqueEligibleVacancies()).isEqualTo(2);
        assertThat(report.eligibleTenantsByProvider()).containsKey("fixture");
        verify(telegram, never()).notifyExcellent(any(), any());
    }

    private RawJob raw(String id, String url, String location, String description) {
        return rawWithTitle(id, url, location, "Java Developer Intern", description);
    }

    private RawJob rawWithTitle(String id, String url, String location, String title,
                                String description) {
        return new RawJob("fixture", id, url, title, "Example",
                location, description, "Internship", null, null, "{}");
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
}
