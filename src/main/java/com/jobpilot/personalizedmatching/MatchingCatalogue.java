package com.jobpilot.personalizedmatching;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.Hashing;
import com.jobpilot.jobs.domain.*;
import com.jobpilot.jobs.repository.JobRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.core.env.Environment;

@Service
public class MatchingCatalogue {
    public record Facts(long jobId,long revision,String extractor,VacancyFacts facts) { }
    private final JdbcTemplate jdbc;private final JobRepository jobs;private final ObjectMapper json;
    private final VacancyFactExtractor extractor;private final Clock clock;private final MatchingProperties settings;private final Environment env;
    private org.springframework.transaction.support.TransactionTemplate bootstrapTransaction;
    @org.springframework.beans.factory.annotation.Autowired void bootstrapTransactions(org.springframework.transaction.PlatformTransactionManager manager){bootstrapTransaction=new org.springframework.transaction.support.TransactionTemplate(manager);bootstrapTransaction.setTimeout(10);bootstrapTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    public MatchingCatalogue(JdbcTemplate jdbc,JobRepository jobs,ObjectMapper json,VacancyFactExtractor extractor,Clock clock,MatchingProperties settings,Environment env){this.jdbc=jdbc;this.jobs=jobs;this.json=json;this.extractor=extractor;this.clock=clock;this.settings=settings;this.env=env;}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean capture(Job job,RawJob raw) {
        jobs.flush();
        jdbc.queryForObject("select id from jobs where id=? for update",Long.class,job.getId());
        var old=jdbc.query("select s.job_revision,s.extractor_version,r.source_hash,r.source_input from vacancy_fact_state s join vacancy_fact_revisions r on r.job_id=s.job_id and r.job_revision=s.job_revision and r.extractor_version=s.extractor_version where s.job_id=?",(rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)},job.getId());
        RawJob source=raw;
        if(source==null&&!old.isEmpty()&&old.getFirst()[3]!=null) {
            try{source=json.readValue((String)old.getFirst()[3],RawJob.class);}catch(JsonProcessingException error){throw new IllegalStateException("Invalid stored neutral source facts",error);}
        }
        String sourceInput=source==null?null:encode(new RawJob(source.source(),source.externalId(),source.url(),source.title(),source.company(),source.location(),source.description(),source.employmentType(),source.publishedAt(),source.deadline(),null,source.locationData(),source.providerTenant(),source.careerData()));
        var facts=extractor.extract(job,source);String payload=encode(facts),hash=Hashing.sha256(payload+"\0"+Objects.toString(sourceInput,""));
        if(!old.isEmpty()&&old.getFirst()[1].equals(VacancyFactExtractor.VERSION)&&old.getFirst()[2].equals(hash))return false;
        long revision=old.isEmpty()?1:(long)old.getFirst()[0]+1;
        jdbc.update("insert into vacancy_fact_revisions(job_id,job_revision,extractor_version,source_hash,source_input,payload,created_at) values(?,?,?,?,?,?,?)",job.getId(),revision,VacancyFactExtractor.VERSION,hash,sourceInput,payload,Timestamp.from(clock.instant()));
        if(old.isEmpty())jdbc.update("insert into vacancy_fact_state(job_id,job_revision,extractor_version) values(?,?,?)",job.getId(),revision,VacancyFactExtractor.VERSION);
        else jdbc.update("update vacancy_fact_state set job_revision=?,extractor_version=? where job_id=?",revision,VacancyFactExtractor.VERSION,job.getId());
        // A row lock, not a sequence: event cursor order is also commit order.
        long next=jdbc.queryForObject("select change_revision from matching_catalogue_state where id=1 for update",Long.class)+1;
        jdbc.update("insert into matching_catalogue_changes values(?,?,?,?)",next,job.getId(),revision,VacancyFactExtractor.VERSION);
        jdbc.update("update matching_catalogue_state set change_revision=? where id=1",next);
        return true;
    }
    public int bootstrap() {
        if(Arrays.asList(env.getActiveProfiles()).contains("recovery"))return 0;
        int captured=0;
        while(captured<settings.bootstrapBatchSize()) {
            Boolean one=bootstrapTransaction.execute(t->{
                com.jobpilot.notifications.NotificationLocks.bound(jdbc);
                var ids=jdbc.queryForList("select j.id from jobs j where not exists(select 1 from vacancy_fact_state s where s.job_id=j.id and s.extractor_version=?) order by j.id limit 1 for update of j skip locked",Long.class,VacancyFactExtractor.VERSION);
                if(ids.isEmpty())return false;
                capture(jobs.findById(ids.getFirst()).orElseThrow(),null);return true;
            });
            if(!Boolean.TRUE.equals(one))break;captured++;
        }
        return captured;
    }
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Cannot encode matching data",e);}}
    public VacancyFacts decode(String value){try{return json.readValue(value,VacancyFacts.class);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid stored vacancy facts",e);}}
}
