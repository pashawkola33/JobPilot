package com.jobpilot.jobreview.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.jobreview.application.JobDetailView;
import com.jobpilot.jobreview.application.JobQueue;
import com.jobpilot.jobreview.application.JobQueueItem;
import com.jobpilot.jobreview.application.JobReasonView;
import com.jobpilot.jobreview.application.JobReviewStats;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.domain.ScreeningReason;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read model for the Telegram review queues. REJECT vacancies and expired vacancies are
 * excluded everywhere; UNREVIEWED is the absence of a job_workflow_state row.
 */
@Repository
public class JobReviewQueryRepository {
    private static final TypeReference<List<ScreeningReason>> REASONS_TYPE = new TypeReference<>() { };

    /** Screened-in and still live. REJECT vacancies carry no score and are never queued. */
    private static final String ACTIVE =
            "j.status <> 'EXPIRED' and j.screening_disposition in ('MATCH', 'REVIEW')";

    private static final String FROM = " from jobs j "
            + "left join job_scores s on s.job_id = j.id "
            + "left join job_workflow_state w on w.job_id = j.id ";

    private static final String COLUMNS = "select j.id, j.title, j.company, j.location, "
            + "j.screening_disposition, coalesce(s.score, 0) score, "
            + "coalesce(w.status, 'UNREVIEWED') workflow_status, j.source, j.provider_tenant, "
            + "coalesce(j.published_at, j.first_seen_at) published_at, j.canonical_url";

    /** UNREVIEWED first, then SAVED, then higher score, then newest, then stable job id. */
    private static final String ORDER = " order by case coalesce(w.status, 'UNREVIEWED') "
            + "when 'UNREVIEWED' then 0 when 'SAVED' then 1 else 2 end, "
            + "coalesce(s.score, 0) desc, "
            + "coalesce(j.published_at, j.first_seen_at) desc, j.id desc";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JobReviewQueryRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long count(JobQueue queue) {
        return jdbc.queryForObject("select count(*)" + FROM + where(queue),
                new MapSqlParameterSource(), Long.class);
    }

    public List<JobQueueItem> findQueue(JobQueue queue, int page, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size);
        return jdbc.query(COLUMNS + FROM + where(queue) + ORDER + " limit :limit offset :offset",
                parameters, itemMapper());
    }

    /** Newly ingested vacancies that are still notifiable, in queue order. */
    public List<JobQueueItem> findNotifiable(ScreeningDisposition disposition,
                                             Collection<Long> jobIds) {
        if (jobIds.isEmpty()) return List.of();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("ids", jobIds)
                .addValue("disposition", disposition.name());
        return jdbc.query(COLUMNS + FROM + " where " + ACTIVE
                        + " and j.id in (:ids) and j.screening_disposition = :disposition"
                        + " and coalesce(w.status, 'UNREVIEWED') <> 'DISMISSED'" + ORDER,
                parameters, itemMapper());
    }

    public Optional<JobDetailView> findDetail(long id) {
        String sql = "select j.id, j.title, j.company, j.location, j.canonical_url, "
                + "coalesce(s.score, 0) score, j.screening_disposition, j.eligibility_reason, "
                + "j.early_career_eligibility_reason, j.screening_reasons::text reason_json, "
                + "j.source, j.provider_tenant, j.published_at, j.first_seen_at, "
                + "coalesce(w.status, 'UNREVIEWED') workflow_status, w.note, w.applied_at"
                + FROM + " where j.id = :id and " + ACTIVE;
        return jdbc.query(sql, new MapSqlParameterSource("id", id), detailMapper())
                .stream().findFirst();
    }

    public JobReviewStats stats() {
        String sql = "select "
                + "count(*) filter (where w.job_id is null "
                + "and j.screening_disposition = 'MATCH') unreviewed_match, "
                + "count(*) filter (where w.job_id is null "
                + "and j.screening_disposition = 'REVIEW') unreviewed_review, "
                + "count(*) filter (where w.status = 'SAVED') saved, "
                + "count(*) filter (where w.status = 'APPLIED') applied, "
                + "count(*) filter (where w.status = 'DISMISSED') dismissed"
                + FROM + " where " + ACTIVE;
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), (rs, row) -> new JobReviewStats(
                rs.getLong("unreviewed_match"), rs.getLong("unreviewed_review"),
                rs.getLong("saved"), rs.getLong("applied"), rs.getLong("dismissed")));
    }

    private String where(JobQueue queue) {
        // Dismissed vacancies stay out of the triage queues but keep their workflow row.
        return " where " + ACTIVE + switch (queue) {
            case MATCHES -> " and j.screening_disposition = 'MATCH'"
                    + " and coalesce(w.status, 'UNREVIEWED') <> 'DISMISSED'";
            case REVIEW -> " and j.screening_disposition = 'REVIEW'"
                    + " and coalesce(w.status, 'UNREVIEWED') <> 'DISMISSED'";
            case SAVED -> " and w.status = 'SAVED'";
            case APPLIED -> " and w.status = 'APPLIED'";
        };
    }

    private RowMapper<JobQueueItem> itemMapper() {
        return (rs, row) -> new JobQueueItem(
                rs.getLong("id"), rs.getString("title"), rs.getString("company"),
                rs.getString("location"),
                enumValue(ScreeningDisposition.class, rs.getString("screening_disposition")),
                rs.getInt("score"),
                enumValue(WorkflowStatus.class, rs.getString("workflow_status")),
                rs.getString("source"), rs.getString("provider_tenant"),
                instant(rs, "published_at"), rs.getString("canonical_url"));
    }

    private RowMapper<JobDetailView> detailMapper() {
        return (rs, row) -> new JobDetailView(
                rs.getLong("id"), rs.getString("title"), rs.getString("company"),
                rs.getString("location"), rs.getString("canonical_url"), rs.getInt("score"),
                enumValue(ScreeningDisposition.class, rs.getString("screening_disposition")),
                rs.getString("eligibility_reason"),
                rs.getString("early_career_eligibility_reason"),
                reasons(rs.getString("reason_json")), rs.getString("source"),
                rs.getString("provider_tenant"), instant(rs, "published_at"),
                instant(rs, "first_seen_at"),
                enumValue(WorkflowStatus.class, rs.getString("workflow_status")),
                rs.getString("note"), instant(rs, "applied_at"));
    }

    private List<JobReasonView> reasons(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, REASONS_TYPE).stream()
                    .map(reason -> new JobReasonView(reason.stage().name(), reason.code(),
                            reason.message())).toList();
        } catch (Exception malformedStoredData) {
            throw new IllegalStateException("Stored screening reasons are invalid", malformedStoredData);
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
