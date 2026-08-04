package com.jobpilot.sources.cleanup;

import com.jobpilot.common.Hashing;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Query-only persistence boundary. This type intentionally has no mutation method. */
@Repository
public class SourceLogCleanupReadRepository {
    private static final List<ProofTable> PROOF_TABLES = List.of(
            new ProofTable("source_fetch_logs", "id",
                    "GREATEST(started_at, COALESCE(finished_at, started_at))"),
            new ProofTable("source_tenant_fetch_logs", "id", "finished_at"),
            new ProofTable("source_tenant_health", "id", "updated_at"),
            new ProofTable("jobs", "id", "last_seen_at"),
            new ProofTable("job_scores", "id", "scored_at"),
            new ProofTable("job_requirements", "id", "NULL::timestamptz"),
            new ProofTable("job_workflow_state", "job_id", "updated_at"),
            new ProofTable("telegram_bot_state", "state_key", "updated_at"),
            new ProofTable("telegram_job_delivery", "id", "created_at"));

    private final JdbcTemplate jdbc;

    public SourceLogCleanupReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TransactionMode transactionMode() {
        return jdbc.queryForObject("""
                SELECT current_setting('transaction_isolation') AS isolation_level,
                       current_setting('transaction_read_only') AS read_only
                """, (rs, row) -> new TransactionMode(
                rs.getString("isolation_level"), rs.getString("read_only")));
    }

    public List<SourceRow> runningRows() {
        return List.copyOf(jdbc.query("""
                SELECT id, ingestion_run_id, source_name, started_at, finished_at, status,
                       fetched_count, saved_count, error_summary
                  FROM source_fetch_logs
                 WHERE status = 'RUNNING'
                 ORDER BY id
                """, this::sourceRow));
    }

    public TenantSummary tenantSummary(UUID runId) {
        List<TenantStateCount> states = jdbc.query("""
                SELECT attempt_status,
                       COUNT(*) AS attempt_count,
                       COUNT(*) FILTER (WHERE finished_at IS NULL
                           OR attempt_status NOT IN ('SUCCESS', 'EMPTY_SUCCESS', 'FAILURE'))
                           AS unfinished_count
                  FROM source_tenant_fetch_logs
                 WHERE ingestion_run_id = ?
                 GROUP BY attempt_status
                 ORDER BY attempt_status
                """, (rs, row) -> new TenantStateCount(rs.getString("attempt_status"),
                rs.getLong("attempt_count"), rs.getLong("unfinished_count")), runId);
        long total = states.stream().mapToLong(TenantStateCount::count).sum();
        long unfinished = states.stream().mapToLong(TenantStateCount::unfinished).sum();
        LinkedHashMap<String, Long> summary = new LinkedHashMap<>();
        states.forEach(state -> summary.put(state.status(), state.count()));
        return new TenantSummary(total, unfinished, summary);
    }

    public LaterTerminalEvidence laterTerminalEvidence(SourceRow source) {
        return jdbc.queryForObject("""
                WITH terminal AS (
                    SELECT id, status, finished_at
                      FROM source_fetch_logs
                     WHERE source_name = ?
                       AND id > ?
                       AND status IN ('SUCCESS', 'FAILED')
                       AND finished_at IS NOT NULL
                )
                SELECT COUNT(*) AS terminal_count,
                       COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success_count,
                       (SELECT id FROM terminal ORDER BY id DESC LIMIT 1) AS latest_id,
                       (SELECT status FROM terminal ORDER BY id DESC LIMIT 1) AS latest_status,
                       (SELECT finished_at FROM terminal ORDER BY id DESC LIMIT 1) AS latest_finished
                  FROM terminal
                """, (rs, row) -> new LaterTerminalEvidence(
                rs.getLong("terminal_count"), rs.getLong("success_count"),
                nullableLong(rs, "latest_id"), rs.getString("latest_status"),
                instant(rs, "latest_finished")), source.sourceName(), source.id());
    }

    public DatabaseProof databaseProof() {
        ArrayList<TableProof> proofs = new ArrayList<>();
        for (ProofTable table : PROOF_TABLES) proofs.add(tableProof(table));
        return new DatabaseProof(proofs);
    }

    private TableProof tableProof(ProofTable table) {
        String sql = "SELECT COUNT(*) AS row_count, "
                + "COALESCE(md5(string_agg(md5(to_jsonb(t)::text), '' ORDER BY "
                + table.orderColumn() + ")), md5('')) AS content_digest, "
                + "MAX(" + table.latestExpression() + ") AS latest_at FROM "
                + table.name() + " t";
        return jdbc.queryForObject(sql, (rs, row) -> {
            long count = rs.getLong("row_count");
            String contentDigest = rs.getString("content_digest");
            Instant latest = instant(rs, "latest_at");
            String envelope = table.name() + "|" + count + "|" + contentDigest + "|"
                    + (latest == null ? "NULL" : latest);
            return new TableProof(table.name(), count, Hashing.sha256(envelope), latest);
        });
    }

    private SourceRow sourceRow(ResultSet rs, int row) throws SQLException {
        return new SourceRow(rs.getLong("id"), rs.getObject("ingestion_run_id", UUID.class),
                rs.getString("source_name"), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("status"),
                rs.getInt("fetched_count"), rs.getInt("saved_count"),
                rs.getString("error_summary"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private record ProofTable(String name, String orderColumn, String latestExpression) {
    }

    private record TenantStateCount(String status, long count, long unfinished) {
    }

    public record TransactionMode(String isolation, String readOnly) {
        public boolean safe() {
            return "repeatable read".equalsIgnoreCase(isolation)
                    && "on".equalsIgnoreCase(readOnly);
        }
    }

    public record SourceRow(long id, UUID ingestionRunId, String sourceName, Instant startedAt,
                            Instant finishedAt, String status, int fetchedCount, int savedCount,
                            String errorSummary) {
    }

    public record TenantSummary(long total, long unfinished, Map<String, Long> terminalStates) {
        public TenantSummary {
            terminalStates = Map.copyOf(terminalStates);
        }

        public String stateSummary() {
            if (terminalStates.isEmpty()) return "NONE";
            return terminalStates.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "," + right)
                    .orElse("NONE");
        }
    }

    public record LaterTerminalEvidence(long terminalCount, long successCount, Long latestId,
                                         String latestStatus, Instant latestFinishedAt) {
    }

    public record TableProof(String table, long rowCount, String fingerprint, Instant latestAt) {
    }

    public record DatabaseProof(List<TableProof> tables) {
        public DatabaseProof {
            tables = List.copyOf(tables);
        }
    }
}
