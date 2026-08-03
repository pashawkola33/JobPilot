package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchLogRepository;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.TelegramNotifier;
import com.jobpilot.telegram.review.TelegramReviewNotifier;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The review push is wired into ingestion but can never influence its outcome.
 * Every source here is a stub; no public ATS endpoint is contacted.
 */
class JobIngestionReviewNotificationTest {
    private TelegramReviewNotifier reviewNotifier;
    private SourceFetchLogRepository logs;
    private JobProcessor processor;

    @BeforeEach
    void setUp() {
        reviewNotifier = mock(TelegramReviewNotifier.class);
        logs = mock(SourceFetchLogRepository.class);
        processor = mock(JobProcessor.class);
        when(logs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private JobIngestionService service(TelegramReviewNotifier notifier) {
        return new JobIngestionService(List.of(new StubSource()),
                new JobRelevanceFilter(TestProperties.create()), processor,
                new LocationEligibilityService(TestProperties.create()),
                new EarlyCareerEligibilityService(),
                logs, mock(TelegramNotifier.class), notifier, Clock.systemUTC());
    }

    private void persistedNew(long jobId, ScreeningDisposition disposition) {
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getScreeningDisposition()).thenReturn(disposition);
        when(processor.process(any(), any(), any(), any()))
                .thenReturn(new JobProcessingResult(job, null, true));
    }

    @Test
    void pushesOnlyTheVacanciesCreatedByTheRunThatJustFinished() {
        persistedNew(42L, ScreeningDisposition.MATCH);

        service(reviewNotifier).fetchAllSources();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> matches = ArgumentCaptor.forClass(Collection.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> reviews = ArgumentCaptor.forClass(Collection.class);
        verify(reviewNotifier).notifyIngestion(matches.capture(), reviews.capture());
        assertThat(matches.getValue()).containsExactly(42L);
        assertThat(reviews.getValue()).isEmpty();
    }

    @Test
    void separatesNewMatchAndReviewVacancies() {
        persistedNew(7L, ScreeningDisposition.REVIEW);

        service(reviewNotifier).fetchAllSources();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> reviews = ArgumentCaptor.forClass(Collection.class);
        verify(reviewNotifier).notifyIngestion(any(), reviews.capture());
        assertThat(reviews.getValue()).containsExactly(7L);
    }

    @Test
    void pushesNothingWhenTheRunPersistedNoNewVacancy() {
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(42L);
        when(job.getScreeningDisposition()).thenReturn(ScreeningDisposition.MATCH);
        // An unchanged duplicate is not newly created and must never be re-announced.
        when(processor.process(any(), any(), any(), any()))
                .thenReturn(new JobProcessingResult(job, null, false));

        service(reviewNotifier).fetchAllSources();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> matches = ArgumentCaptor.forClass(Collection.class);
        verify(reviewNotifier).notifyIngestion(matches.capture(), any());
        assertThat(matches.getValue()).isEmpty();
    }

    @Test
    void completesTheRunWhenTheReviewNotifierThrows() {
        persistedNew(42L, ScreeningDisposition.MATCH);
        doThrow(new IllegalStateException("telegram unavailable"))
                .when(reviewNotifier).notifyIngestion(any(), any());

        JobIngestionReport report = service(reviewNotifier).fetchAllSources();

        assertThat(report.totalVacanciesFetched()).isEqualTo(1);
        assertThat(report.persistedNewVacancies()).isEqualTo(1);
        verify(reviewNotifier).notifyIngestion(any(), any());
    }

    @Test
    void skipsTheReviewPushEntirelyWhenNoNotifierIsWired() {
        persistedNew(42L, ScreeningDisposition.MATCH);

        assertThat(service(null).fetchAllSources().persistedNewVacancies()).isEqualTo(1);

        verifyNoInteractions(reviewNotifier);
    }

    private static final class StubSource implements JobSource {
        @Override
        public String getSourceName() {
            return "fixture";
        }

        @Override
        public List<RawJob> fetchJobs() {
            return List.of(new RawJob("fixture", "1", "https://example.test/jobs/1",
                    "Java Developer Intern", "Example", "Bucharest, Romania",
                    "Java internship in Bucharest with Spring Boot and SQL mentorship.",
                    "Internship", null, null, "{}"));
        }
    }
}
