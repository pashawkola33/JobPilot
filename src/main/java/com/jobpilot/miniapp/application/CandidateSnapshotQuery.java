package com.jobpilot.miniapp.application;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.miniapp.api.MiniAppSnapshot;
import com.jobpilot.miniapp.api.MiniAppSnapshot.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Neutral shared catalogue plus private projections. No score/reason table is read by this query. */
@Repository
public class CandidateSnapshotQuery {
    private static final int JOB_LIMIT=50, APPLICATION_LIMIT=20;
    private static final String FROM=" from jobs j left join job_workflow_state w on w.job_id=j.id and w.candidate_id=:owner ";
    private static final String ACTIVE=" j.status <> 'EXPIRED' ";
    private static final String ORDER=" order by coalesce(j.published_at,j.first_seen_at) desc,j.id desc ";
    private static final String JOB_COLUMNS="j.id,j.title,j.company,j.location,j.remote_type,j.seniority_level,j.employment_type,j.source,j.canonical_url,coalesce(j.published_at,j.first_seen_at) published_at,coalesce(w.status,'UNREVIEWED') workflow_status";
    private final NamedParameterJdbcTemplate jdbc;
    public CandidateSnapshotQuery(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public MiniAppSnapshot snapshot(long owner) {
        var params=new MapSqlParameterSource("owner",owner);
        Map<String,Object> workflow=jdbc.queryForMap("select count(*) filter(where w.job_id is null) review, count(*) filter(where w.status='SAVED') saved,count(*) filter(where w.status='APPLIED') applied,count(*) filter(where w.status='DISMISSED') dismissed"+FROM+" where "+ACTIVE,params);
        List<MiniAppJob> review=jobs(params," and w.job_id is null");
        List<MiniAppJob> saved=jobs(params," and w.status='SAVED'");
        List<MiniAppApplication> apps=jdbc.query("select "+JOB_COLUMNS+",a.status application_status,a.application_date,a.next_follow_up_date,a.updated_at from applications a join jobs j on j.id=a.job_id left join job_workflow_state w on w.job_id=j.id and w.candidate_id=:owner where a.candidate_id=:owner order by a.updated_at desc,a.id desc limit "+APPLICATION_LIMIT,params,(r,n)->new MiniAppApplication(r.getLong("id"),r.getString("title"),r.getString("company"),ApplicationStatus.valueOf(r.getString("application_status")),r.getString("canonical_url"),instant(r,"updated_at"),instant(r,"application_date"),r.getString("next_follow_up_date"),job(r)));
        Map<String,Object> counts=jdbc.queryForMap("select count(*) total,count(*) filter(where status='SAVED') saved,count(*) filter(where status='APPLIED') applied,count(*) filter(where status='INTERVIEW') interview,count(*) filter(where status='OFFER') offer,count(*) filter(where status='REJECTED') rejected,count(*) filter(where status='WITHDRAWN') withdrawn from applications where candidate_id=:owner",params);
        List<Long> revisions=jdbc.query("select mutation_revision from mini_app_state where candidate_id=:owner",params,(r,n)->r.getLong(1));
        long reviewTotal=number(workflow,"review"),savedTotal=number(workflow,"saved"),appTotal=number(counts,"total");
        return new MiniAppSnapshot(revisions.isEmpty()?0:revisions.getFirst(),
                new MiniAppJobPage(review,reviewTotal,JOB_LIMIT,reviewTotal>review.size()),
                new MiniAppJobPage(saved,savedTotal,JOB_LIMIT,savedTotal>saved.size()),
                new MiniAppApplicationPage(apps,appTotal,APPLICATION_LIMIT,appTotal>apps.size()),
                new MiniAppWorkflowCounts(0,reviewTotal,savedTotal,number(workflow,"applied"),number(workflow,"dismissed")),
                new MiniAppApplicationCounts(appTotal,number(counts,"saved"),number(counts,"applied"),number(counts,"interview"),number(counts,"offer"),number(counts,"rejected"),number(counts,"withdrawn")));
    }
    private List<MiniAppJob> jobs(MapSqlParameterSource params,String condition) {
        return jdbc.query("select "+JOB_COLUMNS+FROM+" where "+ACTIVE+condition+ORDER+" limit "+JOB_LIMIT,params,(r,n)->job(r));
    }
    private MiniAppJob job(ResultSet r) throws SQLException {
        return new MiniAppJob(r.getLong("id"),r.getString("title"),r.getString("company"),r.getString("location"),r.getString("remote_type"),r.getString("seniority_level"),r.getString("employment_type"),0,null,ScreeningDisposition.REVIEW,WorkflowStatus.valueOf(r.getString("workflow_status")),r.getString("source"),instant(r,"published_at"),r.getString("canonical_url"),List.of(),List.of());
    }
    private static Instant instant(ResultSet r,String column) throws SQLException { var value=r.getTimestamp(column);return value==null?null:value.toInstant(); }
    private static long number(Map<String,Object> row,String name) { return ((Number)row.get(name)).longValue(); }
}
