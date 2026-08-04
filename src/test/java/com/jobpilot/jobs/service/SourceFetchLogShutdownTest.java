package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchFailureCategory;
import com.jobpilot.sources.SourceFetchLogHandle;
import com.jobpilot.sources.SourceFetchLogLifecycleService;
import com.jobpilot.sources.SourceFetchLogTerminalOutcome;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.TelegramNotifier;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * A graceful scheduler shutdown must still leave the source log terminal.
 *
 * <p>The source blocks on a latch rather than on a socket, so this exercises the interrupt
 * path with no live HTTP request and no database.
 */
class SourceFetchLogShutdownTest {
    private final SourceFetchLogLifecycleService lifecycle =
            mock(SourceFetchLogLifecycleService.class);

    @Test
    void aSchedulerShutdownInterruptsTheSourceAndStillFinalizesTheRow() throws Exception {
        AtomicLong ids = new AtomicLong();
        when(lifecycle.begin(anyString(), any(), any())).thenAnswer(invocation ->
                new SourceFetchLogHandle(ids.incrementAndGet(), invocation.getArgument(0),
                        invocation.getArgument(1)));
        when(lifecycle.fail(any(), any(), any(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.UPDATED);
        when(lifecycle.succeed(any(), anyInt(), anyInt(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.UPDATED);

        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean interruptSeenInsideSource = new AtomicBoolean();

        JobSource blocking = new JobSource() {
            @Override
            public String getSourceName() {
                return "greenhouse";
            }

            @Override
            public List<RawJob> fetchJobs() {
                sourceEntered.countDown();
                try {
                    // Stands in for a long HTTP call parked on a socket.
                    neverReleased.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    interruptSeenInsideSource.set(true);
                    // Same contract as ExternalHttpClient: re-assert the flag, then translate
                    // into an unchecked exception for the lifecycle boundary to classify.
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("transport interrupted");
                }
                return List.of();
            }
        };

        JobIngestionService service = new JobIngestionService(List.of(blocking),
                new JobRelevanceFilter(TestProperties.create()), mock(JobProcessor.class),
                new LocationEligibilityService(TestProperties.create()),
                new EarlyCareerEligibilityService(), lifecycle, mock(TelegramNotifier.class),
                Clock.systemUTC());

        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("shutdown-test-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.initialize();

        CountDownLatch ingestionReturned = new CountDownLatch(1);
        scheduler.schedule(() -> {
            try {
                service.fetchAllSources();
            } finally {
                ingestionReturned.countDown();
            }
        }, java.time.Instant.now());

        assertThat(sourceEntered.await(10, TimeUnit.SECONDS)).isTrue();

        // Graceful shutdown: interrupt in flight, then wait for the task to unwind.
        scheduler.shutdown();

        assertThat(ingestionReturned.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(interruptSeenInsideSource.get()).isTrue();

        // The row reached a terminal state before the context finished closing.
        ArgumentCaptor<SourceFetchFailureCategory> category =
                ArgumentCaptor.forClass(SourceFetchFailureCategory.class);
        verify(lifecycle).fail(any(), category.capture(), any(), any());
        assertThat(category.getValue()).isEqualTo(SourceFetchFailureCategory.PROCESS_INTERRUPTED);
        verify(lifecycle, org.mockito.Mockito.never()).succeed(any(), anyInt(), anyInt(), any());
    }

    @Test
    void theHandleIsStillUsableAfterTheEntityWouldHaveBeenDetached() {
        // The boundary keeps identity only, so a terminal write never depends on a live
        // persistence context surviving the whole source run.
        SourceFetchLogHandle handle = new SourceFetchLogHandle(9L, "ashby", UUID.randomUUID());

        assertThat(handle.id()).isEqualTo(9L);
        assertThat(handle).isEqualTo(new SourceFetchLogHandle(9L, "ashby", handle.ingestionRunId()));
    }
}
