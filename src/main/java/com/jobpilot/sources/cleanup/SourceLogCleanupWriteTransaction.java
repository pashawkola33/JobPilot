package com.jobpilot.sources.cleanup;

import com.jobpilot.sources.cleanup.SourceLogCleanupReadRepository.SourceRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL-specific, locked, all-or-nothing transition of the approved historical set. */
@Service
public class SourceLogCleanupWriteTransaction {
    private static final String TERMINAL_STATUS =
            SourceLogCleanupPreviewService.PROPOSED_STATUS;
    private static final String ERROR_SUMMARY =
            SourceLogCleanupPreviewService.PROPOSED_ERROR_SUMMARY;

    private final JdbcTemplate jdbc;

    public SourceLogCleanupWriteTransaction(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public SourceLogCleanupWriteResult apply(SourceLogCleanupPlan plan) {
        List<Long> ids = plan.expectedRunningIds();
        if (ids.size() != SourceLogCleanupWriteGuards.REQUIRED_CANDIDATE_COUNT) {
            throw abort("TARGET_COUNT_CHANGED");
        }

        jdbc.execute("LOCK TABLE source_fetch_logs IN SHARE ROW EXCLUSIVE MODE");
        String readOnly = jdbc.queryForObject(
                "SELECT current_setting('transaction_read_only')", String.class);
        if (!"off".equalsIgnoreCase(readOnly)) throw abort("WRITE_TRANSACTION_READ_ONLY");

        List<SourceRow> locked = rowsByIds(ids, true);
        if (locked.size() != ids.size()) throw abort("TARGET_ROW_MISSING");
        List<Long> completeRunningSet = runningRowsForUpdate().stream()
                .map(SourceRow::id).toList();
        if (!completeRunningSet.equals(ids)) throw abort("COMPLETE_RUNNING_SET_CHANGED");

        for (int index = 0; index < ids.size(); index++) {
            SourceLogCleanupPlanEntry expected = plan.entries().get(index);
            SourceRow current = locked.get(index);
            if (expected.id() != ids.get(index) || !current.equals(original(expected))) {
                throw abort("TARGET_ROW_CHANGED_AFTER_PLANNING");
            }
            if (!"RUNNING".equals(current.status()) || current.finishedAt() != null) {
                throw abort("TARGET_ROW_NOT_OPEN_RUNNING");
            }
        }

        Timestamp transactionTimestamp = jdbc.queryForObject(
                "SELECT transaction_timestamp()", Timestamp.class);
        if (transactionTimestamp == null) throw abort("TRANSACTION_TIMESTAMP_MISSING");
        Instant finishedAt = transactionTimestamp.toInstant();
        int totalUpdated = 0;
        for (Long id : ids) {
            int updated = jdbc.update("""
                    UPDATE source_fetch_logs
                       SET status = 'FAILED', finished_at = ?, error_summary = ?
                     WHERE id = ?
                       AND status = 'RUNNING'
                       AND finished_at IS NULL
                    """, Timestamp.from(finishedAt), ERROR_SUMMARY, id);
            if (updated != 1) throw abort("CONDITIONAL_UPDATE_COUNT_MISMATCH");
            totalUpdated += updated;
        }
        if (totalUpdated != SourceLogCleanupWriteGuards.REQUIRED_CANDIDATE_COUNT) {
            throw abort("TOTAL_UPDATE_COUNT_MISMATCH");
        }
        if (!runningRowsForUpdate().isEmpty()) throw abort("RUNNING_ROWS_REMAIN");

        List<SourceRow> terminal = rowsByIds(ids, false);
        if (terminal.size() != ids.size()) throw abort("TERMINAL_ROW_MISSING");
        for (int index = 0; index < terminal.size(); index++) {
            verifyTerminal(plan.entries().get(index), terminal.get(index), finishedAt);
        }
        return SourceLogCleanupWriteResult.success(totalUpdated, ids, finishedAt);
    }

    private List<SourceRow> runningRowsForUpdate() {
        return List.copyOf(jdbc.query("""
                SELECT id, ingestion_run_id, source_name, started_at, finished_at, status,
                       fetched_count, saved_count, error_summary
                  FROM source_fetch_logs
                 WHERE status = 'RUNNING'
                 ORDER BY id
                   FOR UPDATE
                """, this::sourceRow));
    }

    private List<SourceRow> rowsByIds(List<Long> ids, boolean forUpdate) {
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = """
                SELECT id, ingestion_run_id, source_name, started_at, finished_at, status,
                       fetched_count, saved_count, error_summary
                  FROM source_fetch_logs
                 WHERE id IN (%s)
                 ORDER BY id
                """.formatted(placeholders) + (forUpdate ? " FOR UPDATE" : "");
        return List.copyOf(jdbc.query(sql, this::sourceRow, ids.toArray()));
    }

    private SourceRow original(SourceLogCleanupPlanEntry entry) {
        return new SourceRow(entry.id(), entry.ingestionRunId(), entry.sourceName(),
                entry.startedAt(), entry.finishedAt(), entry.currentStatus(),
                entry.fetchedCount(), entry.savedCount(), entry.existingErrorSummary());
    }

    private void verifyTerminal(SourceLogCleanupPlanEntry expected, SourceRow actual,
                                Instant finishedAt) {
        if (actual.id() != expected.id()
                || !Objects.equals(actual.ingestionRunId(), expected.ingestionRunId())
                || !Objects.equals(actual.sourceName(), expected.sourceName())
                || !Objects.equals(actual.startedAt(), expected.startedAt())
                || !TERMINAL_STATUS.equals(actual.status())
                || !Objects.equals(actual.finishedAt(), finishedAt)
                || actual.fetchedCount() != expected.fetchedCount()
                || actual.savedCount() != expected.savedCount()
                || !ERROR_SUMMARY.equals(actual.errorSummary())) {
            throw abort("TERMINAL_RESULT_MISMATCH");
        }
    }

    private SourceRow sourceRow(ResultSet rs, int row) throws SQLException {
        return new SourceRow(rs.getLong("id"), rs.getObject("ingestion_run_id", UUID.class),
                rs.getString("source_name"), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("status"),
                rs.getInt("fetched_count"), rs.getInt("saved_count"),
                rs.getString("error_summary"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private IllegalStateException abort(String safeReason) {
        return new IllegalStateException(safeReason);
    }
}
