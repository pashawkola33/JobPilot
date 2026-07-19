package com.jobpilot.telegram.polling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.api.TelegramClient;
import com.jobpilot.telegram.api.TelegramUpdate;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TelegramUpdatePollerTest {
    private TelegramClient client;
    private TelegramUpdateProcessor processor;
    private TelegramBotStateService state;

    @BeforeEach
    void setUp() {
        client = org.mockito.Mockito.mock(TelegramClient.class);
        processor = org.mockito.Mockito.mock(TelegramUpdateProcessor.class);
        state = org.mockito.Mockito.mock(TelegramBotStateService.class);
    }

    @Test
    void pollingIsDisabledByDefault() {
        TelegramUpdatePoller poller = new TelegramUpdatePoller(client, processor, state,
                TestProperties.create());
        assertThat(poller.pollOnce()).isFalse();
        verify(client, never()).getUpdates(any(), any(), anyInt());
    }

    @Test
    void requestsNextOffsetSortsUpdatesAndIgnoresDuplicates() {
        when(state.load()).thenReturn(Optional.of(
                new TelegramBotStateService.StateSnapshot(5L, null, 0)));
        when(client.getUpdates(6L, Duration.ofSeconds(25), 50))
                .thenReturn(List.of(update(7), update(5), update(6)));
        TelegramUpdatePoller poller = poller(true, 3);

        poller.pollOnce();

        var order = inOrder(processor, state);
        order.verify(processor).process(update(6));
        order.verify(state).markProcessed(6);
        order.verify(processor).process(update(7));
        order.verify(state).markProcessed(7);
        verify(processor, never()).process(update(5));
    }

    @Test
    void transientFailurePersistsRetryAndStopsLaterUpdates() {
        when(state.load()).thenReturn(Optional.of(
                new TelegramBotStateService.StateSnapshot(5L, null, 0)));
        when(client.getUpdates(any(), any(), anyInt())).thenReturn(List.of(update(6), update(7)));
        org.mockito.Mockito.doThrow(new IllegalStateException("safe internal defect"))
                .when(processor).process(update(6));
        when(state.recordFailure(6)).thenReturn(1);

        poller(true, 3).pollOnce();

        verify(state).recordFailure(6);
        verify(state, never()).markProcessed(6);
        verify(processor, never()).process(update(7));
    }

    @Test
    void poisonUpdateIsDeadLetteredAtConfiguredAttemptAndProcessingContinues() {
        when(state.load()).thenReturn(Optional.of(
                new TelegramBotStateService.StateSnapshot(5L, 6L, 2)));
        when(client.getUpdates(any(), any(), anyInt())).thenReturn(List.of(update(6), update(7)));
        org.mockito.Mockito.doThrow(new IllegalStateException("safe internal defect"))
                .when(processor).process(update(6));
        when(state.recordFailure(6)).thenReturn(3);

        poller(true, 3).pollOnce();

        verify(state).markProcessed(6);
        verify(processor).process(update(7));
        verify(state).markProcessed(7);
    }

    @Test
    void firstStartDiscardsBacklogWhenConfigured() {
        when(state.load()).thenReturn(Optional.empty());
        when(client.getUpdates(isNull(), eq(Duration.ZERO), eq(50)))
                .thenReturn(List.of(update(2), update(1)));

        poller(true, 3).pollOnce();

        verify(state).initialize(2L);
        verify(state).markProcessed(2L);
        verify(processor, never()).process(any());
    }

    @Test
    void firstStartDiscardsEveryPageUntilEmptyIncludingLaterPartialPage() {
        when(state.load()).thenReturn(Optional.empty());
        when(client.getUpdates(org.mockito.ArgumentMatchers.nullable(Long.class),
                eq(Duration.ZERO), eq(2)))
                .thenReturn(List.of(update(2), update(1)))
                .thenReturn(List.of(update(4), update(3)))
                .thenReturn(List.of(update(5)))
                .thenReturn(List.of());

        poller(true, 3, 2).pollOnce();

        verify(client).getUpdates(null, Duration.ZERO, 2);
        verify(client).getUpdates(3L, Duration.ZERO, 2);
        verify(client).getUpdates(5L, Duration.ZERO, 2);
        verify(client).getUpdates(6L, Duration.ZERO, 2);
        verify(state).markProcessed(2L);
        verify(state).markProcessed(4L);
        verify(state).markProcessed(5L);
        verify(processor, never()).process(any());
        verify(state, never()).recordFailure(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void firstStartDiscardHasFiniteSafetyPageBoundForFaultyClient() {
        when(state.load()).thenReturn(Optional.empty());
        when(client.getUpdates(org.mockito.ArgumentMatchers.nullable(Long.class),
                eq(Duration.ZERO), eq(2))).thenAnswer(invocation -> {
                    Long offset = invocation.getArgument(0);
                    long first = offset == null ? 1L : offset;
                    return List.of(update(first), update(first + 1));
                });

        poller(true, 3, 2).pollOnce();

        verify(client, times(TelegramUpdatePoller.MAX_FIRST_START_DISCARD_PAGES))
                .getUpdates(org.mockito.ArgumentMatchers.nullable(Long.class), eq(Duration.ZERO), eq(2));
        verify(processor, never()).process(any());
    }

    @Test
    void firstStartProcessesBacklogWhenDiscardIsFalse() {
        when(state.load()).thenReturn(Optional.empty());
        when(client.getUpdates(isNull(), eq(Duration.ofSeconds(25)), eq(50)))
                .thenReturn(List.of(update(2), update(1)));

        poller(false, 3).pollOnce();

        var order = inOrder(processor);
        order.verify(processor).process(update(1));
        order.verify(processor).process(update(2));
        verify(state).markProcessed(1);
        verify(state).markProcessed(2);
    }

    @Test
    void atomicGuardPreventsOverlappingLongPolls() throws Exception {
        when(state.load()).thenReturn(Optional.of(
                new TelegramBotStateService.StateSnapshot(5L, null, 0)));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.getUpdates(any(), any(), anyInt())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return List.of();
        });
        TelegramUpdatePoller poller = poller(true, 3);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(poller::pollOnce);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(poller.pollOnce()).isFalse();
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TelegramUpdatePoller poller(boolean discard, int maxFailures) {
        return poller(discard, maxFailures, 50);
    }

    private TelegramUpdatePoller poller(boolean discard, int maxFailures, int pollLimit) {
        JobPilotProperties.Telegram telegram = new JobPilotProperties.Telegram(
                "obviously-fake-token", "-100555", "JobPilotBot", true, "-100555", "777",
                Duration.ofSeconds(25), Duration.ofSeconds(2), pollLimit, maxFailures, discard);
        return new TelegramUpdatePoller(client, processor, state, TestProperties.create(telegram));
    }

    private TelegramUpdate update(long id) {
        return new TelegramUpdate(id, null, null);
    }
}
