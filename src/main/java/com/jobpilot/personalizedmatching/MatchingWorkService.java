package com.jobpilot.personalizedmatching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.searchprofile.SearchProfile;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.env.Environment;

@Service
public class MatchingWorkService {
    public record Claim(long candidateId,long profileRevision,long token,String phase,long nextJob,long highWater,long eventCursor) { }
    public record Calculation(MatchingCatalogue.Facts source,PersonalizedMatcher.Match result) { }
    public record Batch(Claim claim,List<Calculation> results,long nextJob,long eventCursor,String phase) { }
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;private final MatchingCatalogue catalogue;
    private final PersonalizedMatcher matcher;private final MatchingProperties settings;private final Clock clock;private final Environment env;
    public MatchingWorkService(JdbcTemplate jdbc,PlatformTransactionManager manager,ObjectMapper json,MatchingCatalogue catalogue,PersonalizedMatcher matcher,MatchingProperties settings,Clock clock,Environment env){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);tx.setTimeout(10);this.json=json;this.catalogue=catalogue;this.matcher=matcher;this.settings=settings;this.clock=clock;this.env=env;}
    private Timestamp now(){return Timestamp.from(clock.instant());}
    private boolean recovery(){return Arrays.asList(env.getActiveProfiles()).contains("recovery");}
    public Claim claim() {
        if(recovery())return null;
        return tx.execute(t->{
            var rows=jdbc.query("select candidate_id,profile_revision,lease_token,phase,next_job_id,high_water_job_id,event_cursor from candidate_matching_state where extractor_version=? and scorer_version=? and phase in ('BACKFILL','READY') and (lease_until is null or lease_until<=?) and (retry_after is null or retry_after<=?) and (phase='BACKFILL' or event_cursor<(select change_revision from matching_catalogue_state where id=1)) order by last_claimed nulls first,candidate_id limit 1 for update skip locked",(r,n)->new Claim(r.getLong(1),r.getLong(2),r.getLong(3)+1,r.getString(4),r.getLong(5),r.getLong(6),r.getLong(7)),VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION,now(),now());
            if(rows.isEmpty())return null;Claim c=rows.getFirst();
            jdbc.update("update candidate_matching_state set lease_token=?,lease_until=?,last_claimed=? where candidate_id=?",c.token(),Timestamp.from(clock.instant().plusSeconds(settings.leaseSeconds())),now(),c.candidateId());
            return c;
        });
    }
    public Batch calculate(Claim c) {
        if(c==null)throw new IllegalArgumentException("Claim required");
        SearchProfile profile;
        try{profile=json.readValue(jdbc.queryForObject("select payload from search_profile_revisions where candidate_id=? and revision=?",String.class,c.candidateId(),c.profileRevision()),SearchProfile.class);}
        catch(java.io.IOException e){throw new IllegalStateException("Invalid stored matching profile",e);}
        String select="select r.job_id,r.job_revision,r.extractor_version,r.payload";
        String join=" join vacancy_fact_revisions r on r.job_id=s.job_id and r.job_revision=s.job_revision and r.extractor_version=s.extractor_version ";
        List<Map<String,Object>> rows;
        boolean backfill=c.phase().equals("BACKFILL");
        if(backfill)rows=jdbc.queryForList(select+",0 as event_revision from vacancy_fact_state s"+join+"where s.job_id>? and s.job_id<=? order by s.job_id limit ?",c.nextJob(),c.highWater(),settings.batchSize());
        else rows=jdbc.queryForList(select+",e.change_revision as event_revision from matching_catalogue_changes e join vacancy_fact_state s on s.job_id=e.job_id"+join+"where e.change_revision>? order by e.change_revision limit ?",c.eventCursor(),settings.batchSize());
        Map<Long,Calculation> results=new LinkedHashMap<>();long next=c.nextJob(),event=c.eventCursor();
        for(var row:rows) {
            long id=((Number)row.get("job_id")).longValue();
            if(backfill)next=id;else event=((Number)row.get("event_revision")).longValue();
            if(!VacancyFactExtractor.VERSION.equals(row.get("extractor_version")))continue;
            var f=new MatchingCatalogue.Facts(id,((Number)row.get("job_revision")).longValue(),(String)row.get("extractor_version"),catalogue.decode((String)row.get("payload")));
            results.put(id,new Calculation(f,matcher.evaluate(profile,f.facts(),clock.instant())));
            if(backfill)next=id;else event=((Number)row.get("event_revision")).longValue();
        }
        String phase=backfill&&!rows.isEmpty()&&next<c.highWater()?"BACKFILL":"READY";
        return new Batch(c,List.copyOf(results.values()),next,event,phase);
    }
    public boolean publish(Batch b) {
        if(recovery())return false;
        return Boolean.TRUE.equals(tx.execute(t->{
            Claim c=b.claim();jdbc.execute("set local lock_timeout='3s'");
            jdbc.queryForObject("select id from candidates where id=? for update",Long.class,c.candidateId());
            var valid=jdbc.queryForList("select candidate_id from candidate_matching_state where candidate_id=? and profile_revision=? and lease_token=? and lease_until>? and extractor_version=? and scorer_version=? for update",Long.class,c.candidateId(),c.profileRevision(),c.token(),now(),VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION);
            if(valid.isEmpty())return false;
            boolean active=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from search_profile_state s join search_profile_revisions r on r.candidate_id=s.candidate_id and r.revision=s.active_revision where s.candidate_id=? and s.active_revision=? and r.completed=true)",Boolean.class,c.candidateId(),c.profileRevision()));
            boolean current=active;
            for(var value:b.results().stream().sorted(Comparator.comparingLong(x->x.source().jobId())).toList()) {
                var f=value.source();var identities=jdbc.query("select job_revision,extractor_version from vacancy_fact_state where job_id=? for update",(rs,n)->rs.getLong(1)==f.revision()&&rs.getString(2).equals(f.extractor()),f.jobId());
                current &= f.extractor().equals(VacancyFactExtractor.VERSION)&&identities.size()==1&&identities.getFirst();
            }
            if(!current){jdbc.update("update candidate_matching_state set lease_until=null where candidate_id=? and lease_token=?",c.candidateId(),c.token());return false;}
            for(var value:b.results()) {
                var f=value.source();var result=value.result();
                jdbc.update("insert into candidate_match_results(candidate_id,job_id,search_profile_version,job_revision,extractor_version,scorer_version,eligibility,compatibility_score,recommendation_score,evidence_coverage,payload,published_at,computed_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(candidate_id,job_id,search_profile_version,job_revision,extractor_version,scorer_version) do nothing",c.candidateId(),f.jobId(),c.profileRevision(),f.revision(),f.extractor(),PersonalizedMatcher.VERSION,result.eligibility().name(),result.compatibilityScore(),result.recommendationScore(),result.evidenceCoverage(),catalogue.encode(result),Timestamp.from(f.facts().publishedAt()),now());
                long id=jdbc.queryForObject("select id from candidate_match_results where candidate_id=? and job_id=? and search_profile_version=? and job_revision=? and extractor_version=? and scorer_version=?",Long.class,c.candidateId(),f.jobId(),c.profileRevision(),f.revision(),f.extractor(),PersonalizedMatcher.VERSION);
                jdbc.update("insert into candidate_match_current(candidate_id,job_id,result_id) values(?,?,?) on conflict(candidate_id,job_id) do update set result_id=excluded.result_id",c.candidateId(),f.jobId(),id);
            }
            jdbc.update("update candidate_matching_state set next_job_id=?,event_cursor=?,phase=?,publication_revision=publication_revision+1,lease_until=null,attempts=0,retry_after=null,failure_category=null where candidate_id=? and lease_token=?",b.nextJob(),b.eventCursor(),b.phase(),c.candidateId(),c.token());
            return true;
        }));
    }
    public boolean runOne() {
        Claim c=claim();if(c==null)return false;
        try{return publish(calculate(c));}
        catch(RuntimeException error){fail(c);return false;}
    }
    private void fail(Claim c) {
        tx.executeWithoutResult(t->{
            var attempts=jdbc.queryForList("select attempts from candidate_matching_state where candidate_id=? and lease_token=? for update",Integer.class,c.candidateId(),c.token());
            if(attempts.isEmpty())return;int n=Math.min(5,attempts.getFirst()+1);
            jdbc.update("update candidate_matching_state set attempts=?,phase=case when ?=5 then 'FAILED' else phase end,lease_until=null,retry_after=?,failure_category='MATCH_COMPUTATION_FAILED' where candidate_id=? and lease_token=?",n,n,Timestamp.from(clock.instant().plusSeconds(Math.min(300,5L<<(n-1)))),c.candidateId(),c.token());
        });
    }
    public void initializeExistingProfiles() {
        if(recovery())return;
        var candidates=jdbc.queryForList("select s.candidate_id from search_profile_state s join search_profile_revisions r on r.candidate_id=s.candidate_id and r.revision=s.active_revision left join candidate_matching_state w on w.candidate_id=s.candidate_id where r.completed=true and (w.candidate_id is null or w.profile_revision<>s.active_revision or w.extractor_version<>? or w.scorer_version<>?) order by s.candidate_id limit ?",Long.class,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION,settings.batchSize());
        for(long candidate:candidates)tx.executeWithoutResult(t->{
            com.jobpilot.notifications.NotificationLocks.bound(jdbc);
            if(jdbc.queryForList("select id from candidates where id=? for update skip locked",Long.class,candidate).isEmpty())return;
            long revision=jdbc.queryForObject("select active_revision from search_profile_state where candidate_id=?",Long.class,candidate);
            boolean needed=!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from candidate_matching_state where candidate_id=? and profile_revision=? and extractor_version=? and scorer_version=?)",Boolean.class,candidate,revision,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION));
            boolean complete=Boolean.TRUE.equals(jdbc.queryForObject("select completed from search_profile_revisions where candidate_id=? and revision=?",Boolean.class,candidate,revision));
            if(needed&&complete)activate(jdbc,candidate,revision,true);
        });
    }
    /** Caller holds the candidate lock and the profile activation transaction. */
    public static void activate(JdbcTemplate jdbc,long candidate,long revision,boolean complete) {
        long watermark=jdbc.queryForObject("select change_revision from matching_catalogue_state where id=1",Long.class);
        long high=jdbc.queryForObject("select coalesce(max(job_id),0) from vacancy_fact_state",Long.class);
        jdbc.update("insert into candidate_matching_state(candidate_id,profile_revision,extractor_version,scorer_version,phase,high_water_job_id,event_cursor) values(?,?,?,?,?,?,?) on conflict(candidate_id) do update set profile_revision=excluded.profile_revision,extractor_version=excluded.extractor_version,scorer_version=excluded.scorer_version,phase=excluded.phase,high_water_job_id=excluded.high_water_job_id,next_job_id=0,event_cursor=excluded.event_cursor,publication_revision=candidate_matching_state.publication_revision+1,lease_token=candidate_matching_state.lease_token+1,lease_until=null,attempts=0,retry_after=null,failure_category=null",candidate,revision,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION,complete?"BACKFILL":"NOT_READY",high,watermark);
    }
}
