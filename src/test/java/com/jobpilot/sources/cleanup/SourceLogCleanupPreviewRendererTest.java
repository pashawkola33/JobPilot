package com.jobpilot.sources.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobpilot.sources.SourceFetchExecutionRegistry;
import com.jobpilot.sources.cleanup.SourceLogCleanupProperties.Guards;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.DatabaseProof;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.LaterTerminalEvidence;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.SourceRow;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TableProof;
import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.TransactionMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceLogCleanupPreviewRendererTest {
    @Test
    void unsafeStoredTextIsNeverRenderedAndEveryLineIsBounded() {
        Instant now = Instant.parse("2026-08-05T09:00:00Z");
        SourceRow row = new SourceRow(69, null,
                "https://api.telegram.org/bot99:TOKEN/get?secret=value",
                Instant.parse("2026-08-02T09:00:00Z"), null, "RUNNING", 0, 0,
                "https://employer.example/job?authorization=SECRET");
        SourceLogCleanupReadRepository repository = mock(SourceLogCleanupReadRepository.class);
        DatabaseProof proof = new DatabaseProof(List.of(
                new TableProof("source_fetch_logs", 2, "a".repeat(64), now)));
        when(repository.transactionMode()).thenReturn(new TransactionMode("repeatable read", "on"));
        when(repository.databaseProof()).thenReturn(proof);
        when(repository.runningRows()).thenReturn(List.of(row));
        when(repository.laterTerminalEvidence(row)).thenReturn(
                new LaterTerminalEvidence(2, 2, 90L, "SUCCESS", now.minusSeconds(1)));
        SourceLogCleanupPreviewService service = new SourceLogCleanupPreviewService(
                repository, new SourceFetchExecutionRegistry(),
                new JvmStartTime(now.minusSeconds(60)), Clock.fixed(now, ZoneOffset.UTC));

        List<String> lines = new SourceLogCleanupPreviewRenderer().render(service.plan(
                new Guards(Duration.ofHours(6), 20, List.of(69L), 1)));
        String rendered = String.join("\n", lines);

        assertThat(lines).allMatch(line -> line.length()
                <= SourceLogCleanupPreviewRenderer.MAX_LINE_LENGTH);
        assertThat(rendered).doesNotContain("https://").doesNotContain("TOKEN")
                .doesNotContain("SECRET").doesNotContain("authorization=")
                .contains("source=redacted-")
                .contains("PROCESS_INTERRUPTED: HistoricalOrphanReconciliation");
    }
}
