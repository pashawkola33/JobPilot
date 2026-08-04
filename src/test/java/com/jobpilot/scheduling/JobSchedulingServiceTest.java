package com.jobpilot.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobIngestionService;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.TelegramNotifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

class JobSchedulingServiceTest {
    @Test
    void dailyDigestSurvivesTelegramFailures() {
        JobIngestionService ingestion = mock(JobIngestionService.class);
        JobScoreRepository scores = mock(JobScoreRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T09:00:00Z"), ZoneOffset.UTC);
        when(scores.findDigest(any(), any(), any())).thenReturn(List.<JobScore>of());
        doThrow(new IllegalStateException("Telegram sendMessage failed: ResourceAccessException"))
                .when(telegram).sendGoodMatchDigest(ArgumentMatchers.<List<JobScore>>any());
        var service = new JobSchedulingService(ingestion, scores, jobs, telegram,
                TestProperties.create(), clock);

        assertThatCode(service::dailyDigest).doesNotThrowAnyException();
    }

    /**
     * The overlap guard still holds after Telegram polling moved to its own scheduler:
     * a second fetch that arrives while one is running is skipped, not queued behind it.
     */
    @Test
    void aSecondFetchIsSkippedWhileOneIsAlreadyRunning() throws Exception {
        JobIngestionService ingestion = mock(JobIngestionService.class);
        JobScoreRepository scores = mock(JobScoreRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        TelegramNotifier telegram = mock(TelegramNotifier.class);
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T09:00:00Z"), ZoneOffset.UTC);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(ingestion.fetchAllSources()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        });
        var service = new JobSchedulingService(ingestion, scores, jobs, telegram,
                TestProperties.create(), clock);

        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            executor.submit(service::fetchJobs);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            service.fetchJobs();   // must return immediately, not block or re-enter

            release.countDown();
        }
        org.mockito.Mockito.verify(ingestion, org.mockito.Mockito.times(1)).fetchAllSources();
    }
}
