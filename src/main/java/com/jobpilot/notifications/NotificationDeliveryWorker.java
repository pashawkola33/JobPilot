package com.jobpilot.notifications;

import static com.jobpilot.notifications.NotificationOutboxService.*;
import com.jobpilot.personalizedmatching.MatchingCatalogue;
import java.net.URI;import java.sql.Timestamp;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;import org.springframework.stereotype.Service;

@Service
public class NotificationDeliveryWorker {
    static final long SENDING_GATE=0x4a504e4f544946L;
    record Claim(long candidate,long generation,long fence,String mode,List<Long> jobs) { }
    static final class Prepared {
        private final Object owner;private final Claim claim;private final long attempt;private final String payload;private final long chat;private final AtomicBoolean consumed=new AtomicBoolean();
        private Prepared(Object owner,Claim claim,long attempt,String payload,long chat){this.owner=owner;this.claim=claim;this.attempt=attempt;this.payload=payload;this.chat=chat;}
    }
    private final Object owner=new Object();private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final NotificationSettingsService settings;private final NotificationRuntimeGate gate;private final Clock clock;private final MatchingCatalogue catalogue;private final NotificationTransport transport;
    public NotificationDeliveryWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,NotificationSettingsService settings,NotificationRuntimeGate gate,Clock clock,MatchingCatalogue catalogue,NotificationTransport transport){this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);tx.setTimeout(15);this.settings=settings;this.gate=gate;this.clock=clock;this.catalogue=catalogue;this.transport=transport;}
    public boolean deliverOne() {
        if(!gate.permitted())return false;
        Prepared prepared=prepareOne();if(prepared==null)return false;dispatch(prepared);return true;
    }
    Claim claimOne() {
        if(!gate.permitted())return null;
        try{return tx.execute(t->{
            NotificationLocks.bound(jdbc);if(!sendingGate())return null;
            var candidates=jdbc.queryForList("select c.id from candidates c join notification_preferences p on p.candidate_id=c.id where exists(select 1 from notification_outbox o where o.candidate_id=c.id and o.state in ('PENDING','RETRY','CLAIMED') and o.next_attempt_at<=? and (o.lease_until is null or o.lease_until<=?)) order by p.last_delivery_check,c.id limit 1 for update of c skip locked",Long.class,now(),now());
            if(candidates.isEmpty()||!gate.permitted())return null;long candidate=candidates.getFirst();var p=preference(candidate);
            jdbc.update("update notification_preferences set last_delivery_check=nextval('notification_delivery_checks') where candidate_id=?",candidate);
            if(!active(candidate,p)){cancelPending(candidate,"CONSENT_OR_CONNECTION");return null;}
            lockPending(candidate);
            jdbc.update("update notification_outbox set state='DROPPED',reason='DISCOVERY_EXPIRED',lease_until=null where candidate_id=? and state in ('PENDING','RETRY','CLAIMED') and expires_at<=?",candidate,now());
            if(quiet(p)){defer(candidate);return null;}
            var rows=jdbc.queryForList("select * from notification_outbox where candidate_id=? and generation=? and state in ('PENDING','RETRY','CLAIMED') and next_attempt_at<=? and expires_at>? and (lease_until is null or lease_until<=?) order by job_id limit 100 for update",candidate,number(p,"generation"),now(),now(),now());
            if(rows.isEmpty())return null;
            String mode=null;Map<String,Object> retry=rows.stream().filter(r->"RETRY".equals(r.get("state"))||"CLAIMED".equals(r.get("state"))).findFirst().orElse(null);
            if(retry!=null)mode=(String)retry.get("mode");
            if(mode==null&&Boolean.TRUE.equals(p.get("immediate"))&&budget(candidate,p,"IMMEDIATE",null))mode="IMMEDIATE";
            if(mode==null&&Boolean.TRUE.equals(p.get("digest"))&&NotificationSchedule.digestDue(clock.instant(),(String)p.get("timezone"),(String)p.get("quiet_start"),(String)p.get("quiet_end"))&&budget(candidate,p,"DIGEST",null))mode="DIGEST";
            if(mode==null||!budget(candidate,p,mode,null)||mode.equals("DIGEST")&&!NotificationSchedule.digestDue(clock.instant(),(String)p.get("timezone"),(String)p.get("quiet_start"),(String)p.get("quiet_end"))){defer(candidate);return null;}
            final String selectedMode=mode;final Map<String,Object> retryRow=retry;
            List<Map<String,Object>> selected=rows.stream().filter(r->retryRow==null?r.get("mode")==null:Objects.equals(r.get("attempt_id"),retryRow.get("attempt_id"))).filter(r->((Number)r.get("attempts")).intValue()<5).limit(mode.equals("IMMEDIATE")?1:10).toList();
            if(selected.isEmpty())return null;
            long fence=selected.stream().mapToLong(r->number(r,"fence")).max().orElse(0)+1;
            List<Long> jobs=new ArrayList<>();for(var row:selected){long job=number(row,"job_id");jdbc.update("update notification_outbox set state='CLAIMED',mode=?,fence=?,lease_until=? where candidate_id=? and job_id=?",selectedMode,fence,Timestamp.from(clock.instant().plusSeconds(30)),candidate,job);jobs.add(job);}
            return new Claim(candidate,number(p,"generation"),fence,mode,List.copyOf(jobs));
        });}catch(org.springframework.dao.DataAccessException e){if(NotificationSqlFailure.transientBeforeDispatch(e))return null;throw e;}
    }
    Prepared prepareOne(){Claim claim=claimOne();return claim==null?null:prepare(claim);}
    Prepared prepare(Claim claim) {
        if(claim==null||!gate.permitted())return null;
        try{return tx.execute(t->{
            NotificationLocks.bound(jdbc);if(!sendingGate()||!candidate(claim.candidate()))return null;var p=preference(claim.candidate());
            lockItems(claim);var rows=logical(claim);
            if(rows.size()!=claim.jobs().size()||!rows.stream().allMatch(r->"CLAIMED".equals(r.get("state"))&&number(r,"fence")==claim.fence()&&instant(r,"lease_until").isAfter(clock.instant())))return null;
            if(!active(claim.candidate(),p)||claim.generation()!=number(p,"generation")||quiet(p)||!budget(claim.candidate(),p,claim.mode(),null)){cancel(claim,"PREPARATION_INVALID");return null;}
            if(claim.mode().equals("DIGEST")&&!NotificationSchedule.digestDue(clock.instant(),(String)p.get("timezone"),(String)p.get("quiet_start"),(String)p.get("quiet_end"))){cancel(claim,"SCHEDULE_CHANGED");return null;}
            var items=validItems(claim,p,rows);var rendered=renderEnvelope(items,claim.mode());
            Set<Long> included=new HashSet<>();for(var item:rendered.items())included.add(number(item,"job_id"));
            for(var row:rows)if(!included.contains(number(row,"job_id")))jdbc.update("update notification_outbox set state='DROPPED',reason='STALE_OR_ENVELOPE_LIMIT',lease_until=null where candidate_id=? and job_id=? and fence=?",claim.candidate(),number(row,"job_id"),claim.fence());
            if(included.isEmpty())return null;
            var sendingRows=rows.stream().filter(r->included.contains(number(r,"job_id"))).toList();
            Claim sending=new Claim(claim.candidate(),claim.generation(),claim.fence(),claim.mode(),sendingRows.stream().map(r->number(r,"job_id")).toList());
            String payload=rendered.payload();
            LocalDate date=clock.instant().atZone(ZoneId.of((String)p.get("timezone"))).toLocalDate();Long envelope=null;
            if(claim.mode().equals("DIGEST")){
                Long previous=sendingRows.getFirst().get("attempt_id")==null?null:number(sendingRows.getFirst(),"attempt_id");
                if(previous!=null)envelope=jdbc.queryForObject("select envelope_id from notification_attempts where id=? and candidate_id=?",Long.class,previous,claim.candidate());
                else {
                    var inserted=jdbc.queryForList("insert into notification_digest_envelopes(candidate_id,local_date,created_at) values(?,?,?) on conflict(candidate_id,local_date) do nothing returning id",Long.class,claim.candidate(),date,now());
                    if(inserted.isEmpty()){cancel(claim,"DIGEST_ALREADY_RESERVED");return null;}envelope=inserted.getFirst();
                }
            }
            long attempt=jdbc.queryForObject("insert into notification_attempts(candidate_id,mode,envelope_id,fence,local_date,timezone,payload,created_at,process_nonce) values(?,?,?,?,?,?,?,?,?) returning id",Long.class,claim.candidate(),claim.mode(),envelope,claim.fence(),date,p.get("timezone"),payload,now(),gate.operatorIdentity().nonce());
            for(var row:sendingRows)jdbc.update("insert into notification_attempt_items(attempt_id,candidate_id,job_id,result_id,generation,fence) values(?,?,?,?,?,?)",attempt,claim.candidate(),number(row,"job_id"),number(row,"result_id"),claim.generation(),claim.fence());
            jdbc.update("insert into notification_attempt_outcomes(attempt_id,state) values(?,'SENDING')",attempt);
            for(long job:sending.jobs())jdbc.update("update notification_outbox set state='SENDING',attempt_id=?,attempts=attempts+1,lease_until=null where candidate_id=? and job_id=? and fence=?",attempt,claim.candidate(),job,claim.fence());
            long chat=jdbc.queryForObject("select chat_id from notification_connections where candidate_id=?",Long.class,claim.candidate());
            return new Prepared(owner,sending,attempt,payload,chat);
        });}catch(org.springframework.dao.DataAccessException e){if(NotificationSqlFailure.transientBeforeDispatch(e))return null;throw e;}
    }
    boolean dispatch(Prepared prepared) {
        if(prepared==null||prepared.owner!=owner||!prepared.consumed.compareAndSet(false,true))return false;
        if(!gate.permitted()){hold(prepared,"DISARMED_BEFORE_DISPATCH");return false;}
        AtomicBoolean external=new AtomicBoolean();
        try {
            boolean done=Boolean.TRUE.equals(tx.execute(t->{
                NotificationLocks.bound(jdbc);if(!sendingGate()||!candidate(prepared.claim.candidate()))return false;var p=preference(prepared.claim.candidate());
                lockItems(prepared.claim);var rows=logical(prepared.claim);
                if(rows.size()!=prepared.claim.jobs().size()||!rows.stream().allMatch(r->"SENDING".equals(r.get("state"))&&r.get("attempt_id")!=null&&number(r,"attempt_id")==prepared.attempt&&number(r,"fence")==prepared.claim.fence()))return false;
                if(!gate.permitted()||!active(prepared.claim.candidate(),p)||number(p,"generation")!=prepared.claim.generation()||quiet(p)||!budget(prepared.claim.candidate(),p,prepared.claim.mode(),prepared.attempt)||validItems(prepared.claim,p,rows).size()!=rows.size()||!modeAllowed(p,prepared.claim.mode())||!attemptDateValid(prepared,p)){finish(prepared,"CANCELED","FINAL_VALIDATION_FAILED",null,0);return true;}
                external.set(true);NotificationTransport.Outcome outcome;
                try{outcome=transport.send(prepared.chat,prepared.payload);}catch(RuntimeException unsafe){outcome=NotificationTransport.Outcome.uncertain();}
                if(outcome==null)outcome=NotificationTransport.Outcome.uncertain();
                String state=outcome.state();
                if(!Set.of("SENT","RETRY","FAILED","BLOCKED","HALT","UNCERTAIN").contains(state)||state.equals("SENT")&&(outcome.messageId()==null||outcome.messageId()<=0)||state.equals("RETRY")&&(outcome.retryAfter()<1||outcome.retryAfter()>3600))outcome=NotificationTransport.Outcome.uncertain();
                state=outcome.state();
                if(state.equals("BLOCKED")||state.equals("HALT")){
                    jdbc.update("update notification_connections set status=? where candidate_id=?",state.equals("BLOCKED")?"BLOCKED":"UNAVAILABLE",prepared.claim.candidate());
                    if(state.equals("HALT"))gate.halt();state="FAILED";
                }
                if(state.equals("RETRY")&&rows.stream().anyMatch(r->number(r,"attempts")>=5))state="FAILED";
                finish(prepared,state,outcome.category(),outcome.messageId(),outcome.retryAfter());return true;
            }));
            if(!done)hold(prepared,"DISPATCH_CONTENTION");return done;
        }catch(RuntimeException error){
            if(!external.get())NotificationSqlFailure.transientBeforeDispatch(error);
            // SENDING is already committed. An outcome-write failure must never re-enable this attempt.
            hold(prepared,external.get()?"OUTCOME_UNCERTAIN":"DISPATCH_REFUSED");return external.get();
        }
    }
    private boolean attemptDateValid(Prepared prepared,Map<String,Object> p){
        var date=jdbc.queryForObject("select local_date from notification_attempts where id=?",java.sql.Date.class,prepared.attempt);
        return date!=null&&date.toLocalDate().equals(clock.instant().atZone(ZoneId.of((String)p.get("timezone"))).toLocalDate());
    }
    private boolean modeAllowed(Map<String,Object> p,String mode){return mode.equals("IMMEDIATE")?Boolean.TRUE.equals(p.get("immediate")):Boolean.TRUE.equals(p.get("digest"))&&NotificationSchedule.digestDue(clock.instant(),(String)p.get("timezone"),(String)p.get("quiet_start"),(String)p.get("quiet_end"));}
    private Timestamp now(){return Timestamp.from(clock.instant());}
    private boolean sendingGate(){return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(?)",Boolean.class,SENDING_GATE));}
    private boolean candidate(long id){return !jdbc.queryForList("select id from candidates where id=? for update skip locked",Long.class,id).isEmpty();}
    private Map<String,Object> preference(long candidate){return jdbc.queryForMap("select * from notification_preferences where candidate_id=? for update",candidate);}
    private boolean active(long candidate,Map<String,Object> p){return Boolean.TRUE.equals(p.get("enabled"))&&!Boolean.TRUE.equals(p.get("paused"))&&settings.consentValid(candidate)&&settings.connectionStatus(candidate).equals("CONNECTED");}
    private boolean quiet(Map<String,Object> p){return NotificationSchedule.quiet(clock.instant(),(String)p.get("timezone"),(String)p.get("quiet_start"),(String)p.get("quiet_end"));}
    private void lockItems(Claim claim) {
        for(long job:claim.jobs().stream().sorted().toList())jdbc.queryForList("select id from jobs where id=? for update",Long.class,job);
        for(long job:claim.jobs().stream().sorted().toList())jdbc.queryForList("select job_id from vacancy_fact_state where job_id=? for update",Long.class,job);
        for(long job:claim.jobs().stream().sorted().toList())jdbc.queryForList("select job_id from candidate_match_current where candidate_id=? and job_id=? for update",Long.class,claim.candidate(),job);
    }
    private List<Map<String,Object>> logical(Claim claim){List<Map<String,Object>> result=new ArrayList<>();for(long job:claim.jobs().stream().sorted().toList())result.addAll(jdbc.queryForList("select * from notification_outbox where candidate_id=? and job_id=? for update",claim.candidate(),job));return result;}
    private List<Map<String,Object>> validItems(Claim claim,Map<String,Object> p,List<Map<String,Object>> rows) {
        List<Map<String,Object>> items=new ArrayList<>();
        for(var row:rows){long job=number(row,"job_id");if(number(row,"generation")!=number(p,"generation")||!instant(row,"expires_at").isAfter(clock.instant()))continue;
            var discoveries=jdbc.queryForList("select discovery_revision,discovered_at from notification_discoveries where job_id=? and discovery_revision>? and discovered_at>? and discovered_at<=?",job,number(p,"baseline_revision"),Timestamp.from(clock.instant().minusSeconds(86400)),now());
            if(discoveries.isEmpty())continue;
            var current=current(jdbc,catalogue,claim.candidate(),job,((Number)p.get("minimum_score")).intValue(),clock.instant());
            if(current.size()==1&&number(current.getFirst(),"result_id")==number(row,"result_id"))items.add(current.getFirst());
        }return items;
    }
    private boolean budget(long candidate,Map<String,Object> p,String mode,Long exclude) {
        LocalDate date=clock.instant().atZone(ZoneId.of((String)p.get("timezone"))).toLocalDate();
        var attempts=jdbc.queryForList("select a.id,a.mode,greatest(a.created_at,coalesce(o.completed_at,a.created_at)) activity_at,a.local_date from notification_attempts a join notification_attempt_outcomes o on o.attempt_id=a.id where a.candidate_id=? and o.state not in ('RETRY','CANCELED') and (a.local_date=? or greatest(a.created_at,coalesce(o.completed_at,a.created_at))>?) order by a.created_at",candidate,date,Timestamp.from(clock.instant().minusSeconds(86400)));
        var relevant=attempts.stream().filter(r->exclude==null||number(r,"id")!=exclude).toList();
        long count=relevant.stream().filter(r->mode.equals(r.get("mode"))).count();
        if(count>=(mode.equals("IMMEDIATE")?3:1)||relevant.size()>=4)return false;
        return !mode.equals("IMMEDIATE")||relevant.stream().noneMatch(r->"IMMEDIATE".equals(r.get("mode"))&&instant(r,"activity_at").isAfter(clock.instant().minusSeconds(60)));
    }
    private void lockPending(long candidate){jdbc.queryForList("select job_id from notification_outbox where candidate_id=? and state in ('PENDING','RETRY','CLAIMED') order by job_id for update",Long.class,candidate);}
    private void defer(long candidate){jdbc.update("update notification_outbox set next_attempt_at=? where candidate_id=? and state in ('PENDING','RETRY','CLAIMED') and (lease_until is null or lease_until<=?)",Timestamp.from(clock.instant().plusSeconds(60)),candidate,now());}
    private void cancelPending(long candidate,String reason){lockPending(candidate);jdbc.update("update notification_outbox set state='CANCELED',reason=?,lease_until=null where candidate_id=? and state in ('PENDING','RETRY','CLAIMED')",reason,candidate);}
    private void cancel(Claim claim,String reason){for(long job:claim.jobs())jdbc.update("update notification_outbox set state='CANCELED',reason=?,lease_until=null where candidate_id=? and job_id=? and fence=? and state='CLAIMED'",reason,claim.candidate(),job,claim.fence());}
    private void finish(Prepared p,String state,String category,Long message,int retry) {
        jdbc.update("update notification_attempt_outcomes set state=?,category=?,telegram_message_id=?,completed_at=? where attempt_id=? and state='SENDING'",state,category,message,now(),p.attempt);
        for(long job:p.claim.jobs())jdbc.update("update notification_outbox set state=?,reason=?,sent_at=?,next_attempt_at=?,lease_until=null where candidate_id=? and job_id=? and attempt_id=? and state='SENDING'",state,category,state.equals("SENT")?now():null,Timestamp.from(clock.instant().plusSeconds(Math.max(0,retry))),p.claim.candidate(),job,p.attempt);
    }
    private void hold(Prepared p,String reason) {
        if(!gate.permitted())return;
        try{tx.executeWithoutResult(t->{NotificationLocks.bound(jdbc);if(!candidate(p.claim.candidate()))return;preference(p.claim.candidate());for(long job:p.claim.jobs().stream().sorted().toList())jdbc.queryForList("select job_id from notification_outbox where candidate_id=? and job_id=? for update",Long.class,p.claim.candidate(),job);finish(p,"UNCERTAIN",reason,null,0);});}catch(RuntimeException ignored){/* The durable SENDING marker remains held. */}
    }
    record Rendered(String payload,List<Map<String,Object>> items) { }
    static String render(List<Map<String,Object>> items,String mode){return renderEnvelope(items,mode).payload();}
    static Rendered renderEnvelope(List<Map<String,Object>> items,String mode) {
        StringBuilder html=new StringBuilder(mode.equals("DIGEST")?"<b>Your new eligible vacancies</b>\n":"<b>New eligible vacancy</b>\n");
        List<Map<String,Object>> included=new ArrayList<>();
        for(var item:items) {
            String url=(String)item.get("canonical_url");
            try{URI uri=URI.create(url);if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)continue;}catch(RuntimeException invalid){continue;}
            StringBuilder card=new StringBuilder();
            card.append("\n<b>").append(escape((String)item.get("title"),80)).append("</b>\n").append(escape((String)item.get("company"),35)).append(" · ").append(escape((String)item.get("location"),35)).append("\nCompatibility: ").append(number(item,"compatibility_score")).append("/100\nConfirmed eligible for your saved profile.\n");
            card.append("<a href=\"").append(escape(url,Integer.MAX_VALUE)).append("\">View vacancy</a>\n");
            if(html.length()+card.length()>4096)continue;
            html.append(card);included.add(item);
        }
        return new Rendered(included.isEmpty()?"":html.toString(),List.copyOf(included));
    }
    private static String escape(String text,int limit){
        StringBuilder escaped=new StringBuilder();
        for(int code:Objects.toString(text,"").codePoints().toArray()){
            String part=switch(code){case '&'->"&amp;";case '<'->"&lt;";case '>'->"&gt;";case '"'->"&quot;";default->new String(Character.toChars(code));};
            if(escaped.length()+part.length()>limit)break;escaped.append(part);
        }return escaped.toString();
    }
}
