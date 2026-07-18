package com.jobpilot.scheduling;

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
}
