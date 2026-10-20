package com.jobpilot.personalizedmatching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.identity.*;
import java.time.*;
import java.util.*;
import java.sql.Timestamp;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class PersonalizedMatchQuery {
    public record State(long activeProfileRevision,String extractorVersion,String scorerVersion,String status,long publicationRevision,
                        long catalogueRevision,long backfillAfterJobId,long backfillHighWaterJobId,long pendingChanges,boolean processingEnabled) { }
    public record Vacancy(long id,String title,String company,String location,String workFormat,String employmentType,String canonicalUrl,String source,Instant publishedAt,String workflowStatus) { }
    public record Computation(long searchProfileVersion,long jobRevision,String extractorVersion,String scorerVersion,Instant computedAt) { }
    public record Item(Vacancy vacancy,PersonalizedMatcher.Match match,Computation computation) { }
    public record Page(List<Item> items,String nextCursor,State state) { }
    public record Detail(Vacancy vacancy,VacancyFacts facts,PersonalizedMatcher.Match match,Computation computation,State state) { }
    public record Cursor(long profile,long publication,long catalogue,String filter,int group,int score,Instant published,long job) { }
    private static final String GROUP="case r.eligibility when 'ELIGIBLE' then 0 when 'UNKNOWN' then 1 else 2 end";
    private static final String FROM=" from candidate_match_results r join candidate_match_current c on c.candidate_id=r.candidate_id and c.job_id=r.job_id and c.result_id=r.id join search_profile_state p on p.candidate_id=r.candidate_id and p.active_revision=r.search_profile_version join search_profile_revisions pr on pr.candidate_id=p.candidate_id and pr.revision=p.active_revision and pr.completed=true join vacancy_fact_state f on f.job_id=r.job_id and f.job_revision=r.job_revision and f.extractor_version=r.extractor_version join vacancy_fact_revisions fr on fr.job_id=f.job_id and fr.job_revision=f.job_revision and fr.extractor_version=f.extractor_version join jobs j on j.id=r.job_id left join job_workflow_state w on w.job_id=j.id and w.candidate_id=r.candidate_id ";
    private static final String CURRENT=" r.candidate_id=:owner and r.extractor_version=:extractor and r.scorer_version=:scorer and j.status<>'EXPIRED' and (j.deadline is null or j.deadline>:now) ";
    private static final String COLUMNS="j.id,j.company,j.canonical_url,j.source,r.search_profile_version,r.job_revision,r.extractor_version,r.scorer_version,r.computed_at,r.compatibility_score,r.eligibility,r.published_at,r.payload match_payload,fr.payload fact_payload,coalesce(w.status,'UNREVIEWED') workflow_status";
    private final NamedParameterJdbcTemplate jdbc;private final TelegramIdentityService identities;private final ObjectMapper json;private final MatchingCatalogue catalogue;private final MatchingProperties settings;private final Clock clock;
    public PersonalizedMatchQuery(NamedParameterJdbcTemplate jdbc,TelegramIdentityService identities,ObjectMapper json,MatchingCatalogue catalogue,MatchingProperties settings,Clock clock){this.jdbc=jdbc;this.identities=identities;this.json=json;this.catalogue=catalogue;this.settings=settings;this.clock=clock;}
    private MapSqlParameterSource parameters(AuthenticatedCandidate p){return new MapSqlParameterSource("owner",p.candidateId()).addValue("extractor",VacancyFactExtractor.VERSION).addValue("scorer",PersonalizedMatcher.VERSION).addValue("now",Timestamp.from(clock.instant()));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public State state(AuthenticatedCandidate p){identities.me(p);return status(p);}
    private State status(AuthenticatedCandidate p) {
        var params=parameters(p);
        long catalogueVersion=jdbc.queryForObject("select change_revision from matching_catalogue_state where id=1",params,Long.class);
        var rows=jdbc.queryForList("select s.active_revision,r.completed,w.profile_revision,w.extractor_version,w.scorer_version,w.phase,w.publication_revision,w.next_job_id,w.high_water_job_id,w.event_cursor from search_profile_state s join search_profile_revisions r on r.candidate_id=s.candidate_id and r.revision=s.active_revision left join candidate_matching_state w on w.candidate_id=s.candidate_id where s.candidate_id=:owner",params);
        if(rows.isEmpty())return new State(0,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION,"NOT_READY",0,catalogueVersion,0,0,0,settings.enabled());
        var row=rows.getFirst();long revision=number(row,"active_revision"),publication=number(row,"publication_revision"),cursor=number(row,"event_cursor");
        boolean current=revision==number(row,"profile_revision")&&VacancyFactExtractor.VERSION.equals(row.get("extractor_version"))&&PersonalizedMatcher.VERSION.equals(row.get("scorer_version"));
        boolean preparing=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from jobs j where not exists(select 1 from vacancy_fact_state f where f.job_id=j.id and f.extractor_version=:extractor))",params,Boolean.class));
        String phase=!Boolean.TRUE.equals(row.get("completed"))?"NOT_READY":!current?"PROCESSING":"FAILED".equals(row.get("phase"))?"FAILED":!"READY".equals(row.get("phase"))||cursor<catalogueVersion||preparing?"PROCESSING":"READY";
        return new State(revision,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION,phase,publication,catalogueVersion,number(row,"next_job_id"),number(row,"high_water_job_id"),Math.max(0,catalogueVersion-cursor),settings.enabled());
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(AuthenticatedCandidate p,String rawLimit,String rawCursor,String filter) {
        identities.me(p);int limit=25;
        if(rawLimit!=null){if(!rawLimit.matches("[1-9][0-9]?"))throw invalid();limit=Integer.parseInt(rawLimit);if(limit>50)throw invalid();}
        String selection=filter==null?"RECOMMENDED":filter;
        if(!Set.of("RECOMMENDED","ELIGIBLE","UNKNOWN","INELIGIBLE","ALL").contains(selection))throw invalid();
        State state=status(p);var params=parameters(p).addValue("limit",limit+1);
        String where=CURRENT+(selection.equals("RECOMMENDED")?" and r.eligibility<>'INELIGIBLE' ":selection.equals("ALL")?"":" and r.eligibility=:eligibility ");params.addValue("eligibility",selection);
        if(rawCursor!=null) {
            Cursor c=decodeCursor(p,rawCursor);
            if(!c.filter().equals(selection))throw invalid();
            if(c.profile()!=state.activeProfileRevision()||c.publication()!=state.publicationRevision()||c.catalogue()!=state.catalogueRevision())throw new IdentityException(409,"MATCH_PAGE_STALE");
            where+=" and ("+GROUP+">:group or ("+GROUP+"=:group and (r.compatibility_score<:score or (r.compatibility_score=:score and (r.published_at<:published or (r.published_at=:published and r.job_id<:job))))))";
            params.addValue("group",c.group()).addValue("score",c.score()).addValue("published",Timestamp.from(c.published())).addValue("job",c.job());
        }
        var rows=jdbc.queryForList("select "+COLUMNS+FROM+" where "+where+" order by "+GROUP+",r.compatibility_score desc,r.published_at desc,r.job_id desc limit :limit",params);
        boolean more=rows.size()>limit;List<Map<String,Object>> selected=rows.subList(0,Math.min(rows.size(),limit));
        String next=null;if(more&&!selected.isEmpty()){var last=selected.getLast();String eligibility=(String)last.get("eligibility");next=encodeCursor(p,new Cursor(state.activeProfileRevision(),state.publicationRevision(),state.catalogueRevision(),selection,eligibility.equals("ELIGIBLE")?0:eligibility.equals("UNKNOWN")?1:2,((Number)last.get("compatibility_score")).intValue(),instant(last,"published_at"),number(last,"id")));}
        return new Page(selected.stream().map(this::item).toList(),next,state);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Detail detail(AuthenticatedCandidate p,long job) {
        identities.me(p);var params=parameters(p).addValue("job",job);
        var facts=jdbc.queryForList("select j.id,j.company,j.canonical_url,j.source,j.title,j.location,j.remote_type,j.employment_type,coalesce(j.published_at,j.first_seen_at) published_at,coalesce(w.status,'UNREVIEWED') workflow_status,r.payload fact_payload from jobs j left join job_workflow_state w on w.job_id=j.id and w.candidate_id=:owner left join vacancy_fact_state f on f.job_id=j.id left join vacancy_fact_revisions r on r.job_id=f.job_id and r.job_revision=f.job_revision and r.extractor_version=f.extractor_version where j.id=:job",params);
        if(facts.isEmpty())throw new IdentityException(404,"JOB_NOT_FOUND");
        var row=facts.getFirst();VacancyFacts f=row.get("fact_payload")==null?null:catalogue.decode((String)row.get("fact_payload"));
        Vacancy vacancy=new Vacancy(job,f==null?(String)row.get("title"):f.title(),(String)row.get("company"),f==null?(String)row.get("location"):f.location(),f==null?(String)row.get("remote_type"):f.workFormat(),f==null?(String)row.get("employment_type"):f.employmentType(),(String)row.get("canonical_url"),(String)row.get("source"),instant(row,"published_at"),(String)row.get("workflow_status"));
        var matches=jdbc.queryForList("select "+COLUMNS+FROM+" where "+CURRENT+" and j.id=:job",params);
        Item match=matches.isEmpty()?null:item(matches.getFirst());
        return new Detail(vacancy,f,match==null?null:match.match(),match==null?null:match.computation(),status(p));
    }
    private Item item(Map<String,Object> r) {
        var facts=catalogue.decode((String)r.get("fact_payload"));PersonalizedMatcher.Match match;
        try{match=json.readValue((String)r.get("match_payload"),PersonalizedMatcher.Match.class);}catch(java.io.IOException e){throw new IllegalStateException("Invalid stored match",e);}
        return new Item(new Vacancy(number(r,"id"),facts.title(),(String)r.get("company"),facts.location(),facts.workFormat(),facts.employmentType(),(String)r.get("canonical_url"),(String)r.get("source"),instant(r,"published_at"),(String)r.get("workflow_status")),match,new Computation(number(r,"search_profile_version"),number(r,"job_revision"),(String)r.get("extractor_version"),(String)r.get("scorer_version"),instant(r,"computed_at")));
    }
    private static long number(Map<String,Object> row,String key){return row.get(key)==null?0:((Number)row.get(key)).longValue();}
    private static Instant instant(Map<String,Object> row,String key){return ((Timestamp)row.get(key)).toInstant();}
    private String encodeCursor(AuthenticatedCandidate p,Cursor cursor){try{byte[] body=json.writeValueAsBytes(cursor);return Base64.getUrlEncoder().withoutPadding().encodeToString(body)+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(p,body));}catch(Exception e){throw new IllegalStateException("Cannot encode match cursor",e);}}
    private Cursor decodeCursor(AuthenticatedCandidate p,String value){try{
        if(value.length()>2048)throw invalid();String[] parts=value.split("\\.",-1);if(parts.length!=2)throw invalid();byte[] body=Base64.getUrlDecoder().decode(parts[0]);
        if(!MessageDigest.isEqual(mac(p,body),Base64.getUrlDecoder().decode(parts[1])))throw invalid();Cursor cursor=json.readValue(body,Cursor.class);
        if(cursor.published()==null||cursor.filter()==null||cursor.group()<0||cursor.group()>2||cursor.score()<0||cursor.score()>100||cursor.job()<1)throw invalid();return cursor;
    }catch(Exception e){throw invalid();}}
    private byte[] mac(AuthenticatedCandidate p,byte[] body)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(p.sessionHash().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return mac.doFinal(body);}
    private IdentityException invalid(){return new IdentityException(400,"INVALID_MATCH_QUERY");}
}
