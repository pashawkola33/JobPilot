package com.jobpilot.sources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceFetchExecutionRegistryTest {
    @Test
    void lifecycleOwnsHandleFromCommittedBeginUntilTerminalAttemptCompletes() {
        SourceFetchLogTerminalWriter writer = mock(SourceFetchLogTerminalWriter.class);
        SourceFetchExecutionRegistry registry = new SourceFetchExecutionRegistry();
        SourceFetchLogLifecycleService lifecycle =
                new SourceFetchLogLifecycleService(writer, registry, Duration.ZERO);
        SourceFetchLogHandle handle = new SourceFetchLogHandle(42, "workday", UUID.randomUUID());
        Instant now = Instant.parse("2026-08-05T09:00:00Z");
        when(writer.begin("workday", handle.ingestionRunId(), now)).thenReturn(handle);
        when(writer.terminalize(anyLong(), anyString(), any(), anyInt(), anyInt(), any()))
                .thenReturn(1);

        assertThat(lifecycle.begin("workday", handle.ingestionRunId(), now)).isEqualTo(handle);
        assertThat(registry.snapshot()).containsExactly(handle);

        lifecycle.succeed(handle, 1, 1, now.plusSeconds(1));

        assertThat(registry.snapshot()).isEmpty();
    }
}
