package com.jobpilot.jobs.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchLogRepository;
import com.jobpilot.telegram.TelegramNotifier;
import java.time.Clock;
import java.util.List;
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
