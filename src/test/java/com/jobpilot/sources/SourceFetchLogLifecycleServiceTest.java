package com.jobpilot.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;

/** Terminal-write semantics and the bounded retry policy, with no database and no network. */
class SourceFetchLogLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-05T09:00:00Z");
    private static final SourceFetchLogHandle HANDLE =
            new SourceFetchLogHandle(42L, "greenhouse", UUID.randomUUID());

    private final SourceFetchLogTerminalWriter writer = mock(SourceFetchLogTerminalWriter.class);
    private final SourceFetchLogLifecycleService lifecycle =
            new SourceFetchLogLifecycleService(writer, Duration.ZERO);

    @Test
    void successReportsUpdatedAndCarriesCountsAndFinishedAt() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn(1);

        assertThat(lifecycle.succeed(HANDLE, 12, 3, NOW))
                .isEqualTo(SourceFetchLogTerminalOutcome.UPDATED);

        verify(writer).terminalize(42L, "SUCCESS", NOW, 12, 3, null);
    }

    @Test
    void failureStoresOnlyABoundedCategoryAndTypeName() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn(1);

        lifecycle.fail(HANDLE, SourceFetchFailureCategory.PROCESS_INTERRUPTED,
                new IllegalStateException("https://api.example.test/bot123:SECRET/getUpdates"), NOW);

        verify(writer).terminalize(42L, "FAILED", NOW, 0, 0,
                "PROCESS_INTERRUPTED: IllegalStateException");
    }

    @Test
    void everyCategoryProducesASafeSummary() {
        assertThat(SourceFetchLogLifecycleService.summary(
                SourceFetchFailureCategory.SOURCE_FAILURE, new IllegalArgumentException("x")))
                .isEqualTo("SOURCE_FAILURE: IllegalArgumentException");
        assertThat(SourceFetchLogLifecycleService.summary(
                SourceFetchFailureCategory.UNCAUGHT_ERROR, new AssertionError("x")))
                .isEqualTo("UNCAUGHT_ERROR: AssertionError");
        assertThat(SourceFetchLogLifecycleService.summary(
                SourceFetchFailureCategory.PROCESS_INTERRUPTED, null))
                .isEqualTo("PROCESS_INTERRUPTED");
    }

    @Test
    void aSummaryNeverCarriesAMessageUrlOrCredential() {
        String summary = SourceFetchLogLifecycleService.summary(
                SourceFetchFailureCategory.SOURCE_FAILURE,
                new IllegalStateException("https://api.telegram.org/bot99:TOKEN/x?key=secret"));

        assertThat(summary).doesNotContain("http").doesNotContain("TOKEN")
                .doesNotContain("secret").doesNotContain("?");
        assertThat(summary.length()).isLessThanOrEqualTo(500);
    }

    @Test
    void aSecondTerminalizationCannotRewriteAnExistingTerminalStatus() {
        // The conditional update matched no row because the status is no longer RUNNING.
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn(0);
        when(writer.exists(42L)).thenReturn(true);

        assertThat(lifecycle.succeed(HANDLE, 1, 1, NOW))
                .isEqualTo(SourceFetchLogTerminalOutcome.ALREADY_TERMINAL);
    }

    @Test
    void aMissingRowFailsClosedRatherThanReportingSuccess() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn(0);
        when(writer.exists(42L)).thenReturn(false);

        SourceFetchLogTerminalOutcome outcome = lifecycle.succeed(HANDLE, 1, 1, NOW);

        assertThat(outcome).isEqualTo(SourceFetchLogTerminalOutcome.MISSING);
        assertThat(outcome.finalized()).isFalse();
    }

    @Test
    void aTransientDatabaseFailureIsRetriedAtMostThreeTimes() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        assertThat(lifecycle.succeed(HANDLE, 1, 1, NOW))
                .isEqualTo(SourceFetchLogTerminalOutcome.FAILED_TO_PERSIST);

        verify(writer, times(SourceFetchLogLifecycleService.MAX_TERMINAL_ATTEMPTS))
                .terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void aTransientFailureThatLaterSucceedsIsFinalized() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new QueryTimeoutException("slow"))
                .thenReturn(1);

        assertThat(lifecycle.succeed(HANDLE, 4, 2, NOW))
                .isEqualTo(SourceFetchLogTerminalOutcome.UPDATED);

        verify(writer, times(2)).terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void recoverableFailuresCountAsTransient() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new RecoverableDataAccessException("connection reset"))
                .thenReturn(1);

        assertThat(lifecycle.succeed(HANDLE, 1, 1, NOW))
                .isEqualTo(SourceFetchLogTerminalOutcome.UPDATED);
    }

    @Test
    void aNonTransientFailureIsNeverRetried() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new DataIntegrityViolationException("constraint"));

        assertThat(lifecycle.succeed(HANDLE, 1, 1, NOW))
                .isEqualTo(SourceFetchLogTerminalOutcome.FAILED_TO_PERSIST);

        verify(writer, times(1)).terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void exhaustedRetriesNeverReportSuccess() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        assertThat(lifecycle.succeed(HANDLE, 1, 1, NOW).finalized()).isFalse();
    }

    @Test
    void theOpeningInsertIsNotRetried() {
        when(writer.begin(anyString(), any(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"));

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> lifecycle.begin("greenhouse", UUID.randomUUID(), NOW))
                .isInstanceOf(CannotAcquireLockException.class);

        verify(writer, times(1)).begin(anyString(), any(), any());
    }

    @Test
    void beginReturnsAnImmutableHandleRatherThanAnEntity() {
        UUID run = UUID.randomUUID();
        when(writer.begin("ashby", run, NOW))
                .thenReturn(new SourceFetchLogHandle(7L, "ashby", run));

        SourceFetchLogHandle handle = lifecycle.begin("ashby", run, NOW);

        assertThat(handle.id()).isEqualTo(7L);
        assertThat(handle.sourceName()).isEqualTo("ashby");
        assertThat(handle.ingestionRunId()).isEqualTo(run);
        assertThat(SourceFetchLogHandle.class.isRecord()).isTrue();
    }

    @Test
    void aRetryPauseThatIsInterruptedKeepsTheInterruptFlag() {
        SourceFetchLogLifecycleService delaying =
                new SourceFetchLogLifecycleService(writer, Duration.ofMillis(50));
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"));
        Thread.currentThread().interrupt();
        try {
            delaying.succeed(HANDLE, 1, 1, NOW);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void nothingIsWrittenBeyondTheConditionalUpdateAndTheExistenceProbe() {
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn(1);

        lifecycle.succeed(HANDLE, 1, 1, NOW);

        verify(writer, never()).exists(anyLong());
        verify(writer, never()).begin(anyString(), any(), any());
    }
}
