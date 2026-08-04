package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchFailureCategory;
import com.jobpilot.sources.SourceFetchLogHandle;
import com.jobpilot.sources.SourceFetchLogLifecycleService;
import com.jobpilot.sources.SourceFetchLogTerminalOutcome;
import com.jobpilot.sources.SourceFetchLogTerminalizationException;
import com.jobpilot.support.TestProperties;
import com.jobpilot.telegram.TelegramNotifier;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * How one source's log row reaches a terminal state on every path the JVM can observe.
 * Every source here is a stub; nothing touches the network or a database.
 */
class SourceFetchLogTerminalizationTest {
    private final SourceFetchLogLifecycleService lifecycle =
            mock(SourceFetchLogLifecycleService.class);
    private final JobProcessor processor = mock(JobProcessor.class);
    private final AtomicLong ids = new AtomicLong();

    /** Defaults only; each test may override succeed/fail before building the service. */
    @org.junit.jupiter.api.BeforeEach
    void stubLifecycleDefaults() {
        when(lifecycle.begin(anyString(), any(), any())).thenAnswer(invocation ->
                new SourceFetchLogHandle(ids.incrementAndGet(), invocation.getArgument(0),
                        invocation.getArgument(1)));
        when(lifecycle.succeed(any(), anyInt(), anyInt(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.UPDATED);
        when(lifecycle.fail(any(), any(), any(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.UPDATED);
    }

    private JobIngestionService service(List<JobSource> sources) {
        return new JobIngestionService(sources, new JobRelevanceFilter(TestProperties.create()),
                processor, new LocationEligibilityService(TestProperties.create()),
                new EarlyCareerEligibilityService(), lifecycle,
                mock(TelegramNotifier.class), Clock.systemUTC());
    }

    private RawJob raw(String id) {
        return new RawJob("fixture", id, "https://example.test/jobs/" + id,
                "Java Developer Intern", "Example", "Bucharest, Romania",
                "Java internship in Bucharest with Spring Boot mentorship.", "Internship",
                null, null, "{}");
    }

    private JobSource source(String name, java.util.function.Supplier<List<RawJob>> supplier) {
        return new JobSource() {
            @Override
            public String getSourceName() {
                return name;
            }

            @Override
            public List<RawJob> fetchJobs() {
                return supplier.get();
            }
        };
    }

    private void persistsNormally() {
        Job job = mock(Job.class);
        when(job.getId()).thenReturn(1L);
        when(job.getScreeningDisposition()).thenReturn(ScreeningDisposition.REVIEW);
        when(processor.process(any(), any(), any(), any()))
                .thenReturn(new JobProcessingResult(job, null, true));
    }

    @Test
    void aSuccessfulSourceOpensOneRowAndFinalizesItExactlyOnce() {
        persistsNormally();

        service(List.of(source("greenhouse", () -> List.of(raw("1"))))).fetchAllSources();

        verify(lifecycle).begin(anyString(), any(), any());
        verify(lifecycle).succeed(any(), anyInt(), anyInt(), any());
        verify(lifecycle, never()).fail(any(), any(), any(), any());
    }

    @Test
    void aRuntimeExceptionFinalizesFailedAndLetsRemainingSourcesRun() {
        persistsNormally();
        List<String> fetched = new ArrayList<>();

        service(List.of(
                source("broken", () -> {
                    fetched.add("broken");
                    throw new IllegalStateException("source is down");
                }),
                source("healthy", () -> {
                    fetched.add("healthy");
                    return List.of(raw("1"));
                }))).fetchAllSources();

        assertThat(fetched).containsExactly("broken", "healthy");
        ArgumentCaptor<SourceFetchFailureCategory> category =
                ArgumentCaptor.forClass(SourceFetchFailureCategory.class);
        verify(lifecycle).fail(any(), category.capture(), any(), any());
        assertThat(category.getValue()).isEqualTo(SourceFetchFailureCategory.SOURCE_FAILURE);
        verify(lifecycle).succeed(any(), anyInt(), anyInt(), any());
    }

    @Test
    void anInterruptedSourceIsFinalizedAsInterruptedAndKeepsTheInterruptFlag() {
        JobSource interrupting = source("greenhouse", () -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("aborted mid-flight");
        });

        try {
            service(List.of(interrupting)).fetchAllSources();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            ArgumentCaptor<SourceFetchFailureCategory> category =
                    ArgumentCaptor.forClass(SourceFetchFailureCategory.class);
            verify(lifecycle).fail(any(), category.capture(), any(), any());
            assertThat(category.getValue())
                    .isEqualTo(SourceFetchFailureCategory.PROCESS_INTERRUPTED);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anInterruptStopsProcessingTheRemainingVacanciesOfThatSource() {
        persistsNormally();
        List<RawJob> many = List.of(raw("1"), raw("2"), raw("3"), raw("4"));
        // Interrupt as soon as the first vacancy is handled.
        when(processor.process(any(), any(), any(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            Job job = mock(Job.class);
            when(job.getId()).thenReturn(1L);
            when(job.getScreeningDisposition()).thenReturn(ScreeningDisposition.REVIEW);
            return new JobProcessingResult(job, null, true);
        });

        try {
            service(List.of(source("greenhouse", () -> many))).fetchAllSources();

            // One vacancy processed, then the loop stopped instead of grinding through the rest.
            verify(processor, org.mockito.Mockito.times(1)).process(any(), any(), any(), any());
            verify(lifecycle).fail(any(),
                    org.mockito.ArgumentMatchers.eq(SourceFetchFailureCategory.PROCESS_INTERRUPTED),
                    any(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anErrorIsTerminalizedBestEffortAndRethrownUnchanged() {
        AssertionError thrown = new AssertionError("jvm level problem");

        assertThatThrownBy(() -> service(List.of(source("greenhouse", () -> {
            throw thrown;
        }))).fetchAllSources()).isSameAs(thrown);

        verify(lifecycle).fail(any(),
                org.mockito.ArgumentMatchers.eq(SourceFetchFailureCategory.UNCAUGHT_ERROR),
                any(), any());
    }

    @Test
    void aFailedTerminalizationOnTheSuccessPathIsSurfacedRatherThanIgnored() {
        persistsNormally();
        when(lifecycle.succeed(any(), anyInt(), anyInt(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.FAILED_TO_PERSIST);

        assertThatThrownBy(() -> service(List.of(source("greenhouse", () -> List.of(raw("1")))))
                .fetchAllSources())
                .isInstanceOf(SourceFetchLogTerminalizationException.class)
                .extracting(e -> ((SourceFetchLogTerminalizationException) e).outcome())
                .isEqualTo(SourceFetchLogTerminalOutcome.FAILED_TO_PERSIST);
    }

    @Test
    void aMissingRowOnTheSuccessPathAlsoFailsClosed() {
        persistsNormally();
        when(lifecycle.succeed(any(), anyInt(), anyInt(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.MISSING);

        assertThatThrownBy(() -> service(List.of(source("greenhouse", () -> List.of(raw("1")))))
                .fetchAllSources())
                .isInstanceOf(SourceFetchLogTerminalizationException.class);
    }

    @Test
    void aFailureDuringFailureFinalizationKeepsTheOriginalCauseAndSuppressesTheOther() {
        // The source fails, and finalizing that failure also fails.
        when(lifecycle.fail(any(), any(), any(), any()))
                .thenReturn(SourceFetchLogTerminalOutcome.FAILED_TO_PERSIST);
        List<Throwable> seen = new ArrayList<>();
        JobSource broken = source("greenhouse", () -> {
            IllegalStateException original = new IllegalStateException("source is down");
            seen.add(original);
            throw original;
        });

        // The run still continues; the original failure carries the finalization problem.
        service(List.of(broken)).fetchAllSources();

        assertThat(seen).singleElement().satisfies(original ->
                assertThat(original.getSuppressed())
                        .hasAtLeastOneElementOfType(SourceFetchLogTerminalizationException.class));
    }

    @Test
    void aThrowingLifecycleDuringFailureFinalizationIsAlsoAttachedAsSuppressed() {
        when(lifecycle.fail(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unreachable"));
        List<Throwable> seen = new ArrayList<>();
        JobSource broken = source("greenhouse", () -> {
            IllegalStateException original = new IllegalStateException("source is down");
            seen.add(original);
            throw original;
        });

        service(List.of(broken)).fetchAllSources();

        assertThat(seen).singleElement().satisfies(original ->
                assertThat(original.getSuppressed()).isNotEmpty());
    }

    @Test
    void theHandleCarriesTheSourceNameAndRunIdAndNoEntity() {
        persistsNormally();
        ArgumentCaptor<SourceFetchLogHandle> handle =
                ArgumentCaptor.forClass(SourceFetchLogHandle.class);

        service(List.of(source("greenhouse", () -> List.of(raw("1"))))).fetchAllSources();

        verify(lifecycle).succeed(handle.capture(), anyInt(), anyInt(), any());
        assertThat(handle.getValue().sourceName()).isEqualTo("greenhouse");
        assertThat(handle.getValue().ingestionRunId()).isInstanceOf(UUID.class);
        assertThat(handle.getValue().id()).isPositive();
    }

    @Test
    void perJobRuntimeIsolationStillContinuesWithinTheSameSource() {
        // A vacancy that blows up must not abort the remaining vacancies of that source.
        when(processor.process(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("bad vacancy"))
                .thenAnswer(invocation -> {
                    Job job = mock(Job.class);
                    when(job.getId()).thenReturn(2L);
                    when(job.getScreeningDisposition()).thenReturn(ScreeningDisposition.REVIEW);
                    return new JobProcessingResult(job, null, true);
                });

        service(List.of(source("greenhouse", () -> List.of(raw("1"), raw("2"))))).fetchAllSources();

        verify(processor, org.mockito.Mockito.times(2)).process(any(), any(), any(), any());
        verify(lifecycle).succeed(any(), anyInt(), anyInt(), any());
        verify(lifecycle, never()).fail(any(), any(), any(), any());
    }
}
