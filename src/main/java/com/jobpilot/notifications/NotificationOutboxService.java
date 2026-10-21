package com.jobpilot.notifications;

import com.jobpilot.personalizedmatching.*;
import java.sql.Timestamp;import java.time.*;import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.stereotype.Service;

@Service
public class NotificationOutboxService {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final NotificationSettingsService settings;private final NotificationRuntimeGate gate;private final Clock clock;private final MatchingCatalogue catalogue;
    public NotificationOutboxService(JdbcTemplate jdbc,PlatformTransactionManager manager,NotificationSettingsService settings,NotificationRuntimeGate gate,Clock clock,MatchingCatalogue catalogue){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);tx.setTimeout(15);this.settings=settings;this.gate=gate;this.clock=clock;this.catalogue=catalogue;}
    public boolean admitOne() {
        if(!gate.permitted())return false;
        try{return Boolean.TRUE.equals(tx.execute(t->{
            NotificationLocks.bound(jdbc);
            var candidates=jdbc.queryForList("select c.id from candidates c join notification_preferences p on p.candidate_id=c.id where p.enabled and not p.paused order by p.last_scanned_at nulls first,c.id limit 1 for update of c skip locked",Long.class);
            if(candidates.isEmpty()||!gate.permitted())return false;long candidate=candidates.getFirst();
            var p=jdbc.queryForMap("select * from notification_preferences where candidate_id=? for update",candidate);
            jdbc.update("update notification_preferences set last_scanned_at=? where candidate_id=?",Timestamp.from(clock.instant()),candidate);
            if(!settings.consentValid(candidate)||!settings.connectionStatus(candidate).equals("CONNECTED"))return false;
            long baseline=number(p,"baseline_revision"),cursor=Math.max(baseline,number(p,"event_cursor"));
            var progress=jdbc.queryForList("select w.event_cursor from candidate_matching_state w join search_profile_state s on s.candidate_id=w.candidate_id and s.active_revision=w.profile_revision join search_profile_revisions r on r.candidate_id=s.candidate_id and r.revision=s.active_revision and r.completed where w.candidate_id=? and w.phase='READY' and w.extractor_version=? and w.scorer_version=?",Long.class,candidate,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION);
            if(progress.isEmpty())return false;
            // Matching publication owns candidate too. Never consume an event ahead of its published progress.
            var events=jdbc.queryForList("select e.job_id,e.change_revision,d.discovery_revision,d.discovered_at from matching_catalogue_changes e left join notification_discoveries d on d.job_id=e.job_id where e.change_revision>? and e.change_revision<=? order by e.change_revision limit 100",cursor,progress.getFirst());
            if(events.isEmpty())return false;
            long pending=jdbc.queryForObject("select count(*) from notification_outbox where candidate_id=? and state in ('PENDING','RETRY','CLAIMED','SENDING')",Long.class,candidate);
            for(var event:events) {
                if(event.get("discovered_at")==null||number(event,"discovery_revision")<=baseline)continue;
                Instant discovered=instant(event,"discovered_at");if(!discovered.isAfter(clock.instant().minusSeconds(86400))||discovered.isAfter(clock.instant()))continue;
                long job=number(event,"job_id");var current=current(jdbc,catalogue,candidate,job,((Number)p.get("minimum_score")).intValue(),clock.instant());
                if(current.isEmpty())continue;
                int inserted=jdbc.update("insert into notification_outbox(candidate_id,job_id,result_id,generation,state,next_attempt_at,expires_at,created_at,reason) values(?,?,?,?,?,?,?,?,?) on conflict(candidate_id,job_id) do nothing",candidate,job,number(current.getFirst(),"result_id"),number(p,"generation"),pending>=100?"DROPPED":"PENDING",Timestamp.from(clock.instant()),Timestamp.from(discovered.plusSeconds(86400)),Timestamp.from(clock.instant()),pending>=100?"CAPACITY_LIMIT":null);
                if(inserted>0&&pending<100)pending++;
            }
            jdbc.update("update notification_preferences set event_cursor=? where candidate_id=?",number(events.getLast(),"change_revision"),candidate);
            return true;
        }));}catch(org.springframework.dao.DataAccessException e){if(NotificationSqlFailure.transientBeforeDispatch(e))return false;throw e;}
    }
    static List<Map<String,Object>> current(JdbcTemplate jdbc,MatchingCatalogue catalogue,long candidate,long job,int minimum,Instant now) {
        var rows=jdbc.queryForList("select r.id result_id,r.job_id,r.search_profile_version,r.job_revision,r.extractor_version,r.scorer_version,r.compatibility_score,r.payload match_payload,fr.payload fact_payload,j.title,j.company,j.location,j.canonical_url from candidate_match_current c join candidate_match_results r on r.candidate_id=c.candidate_id and r.job_id=c.job_id and r.id=c.result_id join search_profile_state s on s.candidate_id=r.candidate_id and s.active_revision=r.search_profile_version join search_profile_revisions pr on pr.candidate_id=s.candidate_id and pr.revision=s.active_revision and pr.completed join vacancy_fact_state f on f.job_id=r.job_id and f.job_revision=r.job_revision and f.extractor_version=r.extractor_version join vacancy_fact_revisions fr on fr.job_id=f.job_id and fr.job_revision=f.job_revision and fr.extractor_version=f.extractor_version join jobs j on j.id=r.job_id where c.candidate_id=? and c.job_id=? and r.eligibility='ELIGIBLE' and r.compatibility_score>=? and r.extractor_version=? and r.scorer_version=? and j.status<>'EXPIRED' and (j.deadline is null or j.deadline>?)",candidate,job,minimum,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION,Timestamp.from(now));
        return rows.stream().filter(r->{var facts=catalogue.decode((String)r.get("fact_payload"));return !facts.status().equals("EXPIRED")&&(facts.deadline()==null||facts.deadline().isAfter(now));}).toList();
    }
    static long number(Map<String,Object> row,String name){return ((Number)row.get(name)).longValue();}
    static Instant instant(Map<String,Object> row,String name){Object value=row.get(name);return value instanceof Timestamp ts?ts.toInstant():((OffsetDateTime)value).toInstant();}
}
