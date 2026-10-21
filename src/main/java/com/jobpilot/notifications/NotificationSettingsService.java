package com.jobpilot.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.identity.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationSettingsService {
    public record Update(Long expectedRevision,String requestId,Boolean enabled,Boolean paused,Boolean immediate,Boolean digest,
                         Integer minimumScore,String timezone,String quietStart,String quietEnd) { }
    public record View(long revision,NotificationPreferences preferences,String connectionStatus,List<String> pauseReasons,
                       boolean legacyManaged,String eligibilityPolicy) { }
    private final JdbcTemplate jdbc;private final TelegramIdentityService identities;private final IdentityProperties identity;
    private NotificationRuntimeGate runtimeGate;
    @org.springframework.beans.factory.annotation.Autowired void runtimeGate(NotificationRuntimeGate gate){runtimeGate=gate;}
    private final NotificationProperties properties;private final Environment env;private final ObjectMapper json;private final Clock clock;
    public NotificationSettingsService(JdbcTemplate jdbc,TelegramIdentityService identities,IdentityProperties identity,
            NotificationProperties properties,Environment env,ObjectMapper json,Clock clock){this.jdbc=jdbc;this.identities=identities;this.identity=identity;this.properties=properties;this.env=env;this.json=json;this.clock=clock;}
    public boolean recovery(){return Arrays.asList(env.getActiveProfiles()).contains("recovery");}
    public void requireNonRecovery(){if(recovery())throw new IdentityException(503,"RECOVERY_MODE");}
    public void requireMutable(){requireNonRecovery();if(!properties.enabled())throw new IdentityException(503,"NOTIFICATIONS_DISABLED");}
    @Transactional(readOnly=true) public View view(AuthenticatedCandidate principal){identities.me(principal);return current(principal);}
    View current(AuthenticatedCandidate p){
        var rows=jdbc.queryForList("select * from notification_preferences where candidate_id=?",p.candidateId());
        long revision=0;NotificationPreferences prefs=NotificationPreferences.defaults();List<String> reasons=new ArrayList<>();
        boolean legacy=identity.isLegacyOwner(p.telegramUserId());
        String connection=connectionStatus(p.candidateId());
        if(!rows.isEmpty()){
            var row=rows.getFirst();revision=((Number)row.get("revision")).longValue();
            prefs=preferences(row);
            if(row.get("revoked_at")!=null||prefs.enabled()&&!consentValid(p.candidateId()))reasons.add("CONSENT_REVOKED");
        }
        if(legacy){connection="LEGACY_MANAGED";reasons.add("LEGACY_MANAGED");}
        else {
            if(!prefs.enabled())reasons.add("DISABLED_BY_USER");if(prefs.paused())reasons.add("PAUSED");
            if(!connection.equals("CONNECTED"))reasons.add(connection.equals("DISCONNECTED")?"CONNECT_BOT":connection);
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from search_profile_state s join search_profile_revisions r on r.candidate_id=s.candidate_id and r.revision=s.active_revision where s.candidate_id=? and r.completed)",Boolean.class,p.candidateId())))reasons.add("INCOMPLETE_PROFILE");
            if(!identity.registrationEnabled()||!identity.admits(p.telegramUserId()))reasons.add("BETA_CLOSED");
        }
        if(!properties.enabled())reasons.add("SYSTEM_DISABLED");if(recovery())reasons.add("RECOVERY");
        if(!legacy&&prefs.enabled()&&properties.enabled()&&!recovery()&&runtimeGate!=null&&!runtimeGate.permitted())reasons.add("DISPATCH_DISARMED");
        if(!legacy&&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from notification_outbox where candidate_id=? and state in ('SENDING','UNCERTAIN'))",Boolean.class,p.candidateId())))reasons.add("UNCERTAIN_HELD");
        return new View(revision,prefs,connection,List.copyOf(reasons),legacy,"ELIGIBLE_ONLY");
    }
    private NotificationPreferences preferences(Map<String,Object> r){return new NotificationPreferences((boolean)r.get("enabled"),(boolean)r.get("paused"),(boolean)r.get("immediate"),(boolean)r.get("digest"),((Number)r.get("minimum_score")).intValue(),(String)r.get("timezone"),(String)r.get("quiet_start"),(String)r.get("quiet_end"));}
    String connectionStatus(long candidate){
        return jdbc.query("select status,credential_scope from notification_connections where candidate_id=?",(r,n)->identities.credentialScope().equals(r.getString(2))?r.getString(1):"DISCONNECTED",candidate).stream().findFirst().orElse("DISCONNECTED");
    }
    /** Session expiry ends browser access, but only explicit revocation ends durable consent. */
    public boolean consentValid(long candidate){
        if(!identity.enabled())return false;
        var rows=jdbc.query("select p.telegram_user_id,p.credential_scope,s.revoked_at from notification_preferences p join auth_sessions s on s.token_hash=p.consent_session_hash and s.candidate_id=p.candidate_id and s.telegram_user_id=p.telegram_user_id join telegram_identities i on i.candidate_id=p.candidate_id and i.telegram_user_id=p.telegram_user_id where p.candidate_id=? and p.enabled and p.revoked_at is null",
            (r,n)->r.getObject(3)==null&&identities.credentialScope().equals(r.getString(2))&&identity.admits(r.getLong(1))&&!identity.isLegacyOwner(r.getLong(1)),candidate);
        return rows.size()==1&&rows.getFirst();
    }
    @Transactional(timeout=15) public View save(AuthenticatedCandidate principal,Update update){
        requireNonRecovery();if(update==null||!Boolean.FALSE.equals(update.enabled()))requireMutable();identities.me(principal);
        if(update==null||update.expectedRevision()==null||update.expectedRevision()<0||update.expectedRevision()==Long.MAX_VALUE||update.requestId()==null||!update.requestId().matches("[A-Za-z0-9_-]{8,64}")||update.enabled()==null||update.paused()==null||update.immediate()==null||update.digest()==null||update.minimumScore()==null)throw invalid();
        var prefs=new NotificationPreferences(update.enabled(),update.paused(),update.immediate(),update.digest(),update.minimumScore(),update.timezone(),update.quietStart(),update.quietEnd());
        if(identity.isLegacyOwner(principal.telegramUserId()))throw new IdentityException(409,"LEGACY_MANAGED");
        NotificationLocks.bound(jdbc).lockCandidate(principal.candidateId());identities.me(principal);
        View active=current(principal);String payload=encode(prefs);
        var replay=jdbc.queryForList("select revision,expected_revision,payload from notification_preference_revisions where candidate_id=? and request_id=?",principal.candidateId(),update.requestId());
        if(!replay.isEmpty()){
            var old=replay.getFirst();if(((Number)old.get("revision")).longValue()!=active.revision()||((Number)old.get("expected_revision")).longValue()!=update.expectedRevision()||!payload.equals(old.get("payload")))throw conflict();
            return active;
        }
        if(active.revision()!=update.expectedRevision())throw conflict();
        Instant now=clock.instant();long next=active.revision()+1;
        jdbc.update("insert into notification_preferences(candidate_id,updated_at) values(?,?) on conflict(candidate_id) do nothing",principal.candidateId(),Timestamp.from(now));
        jdbc.update("update notification_preferences set revision=?,enabled=?,paused=?,immediate=?,digest=?,minimum_score=?,timezone=?,quiet_start=?,quiet_end=?,consent_session_hash=?,telegram_user_id=?,credential_scope=?,consent_policy=?,consent_at=?,revoked_at=null,updated_at=? where candidate_id=?",
            next,prefs.enabled(),prefs.paused(),prefs.immediate(),prefs.digest(),prefs.minimumScore(),prefs.timezone(),prefs.quietStart(),prefs.quietEnd(),principal.sessionHash(),principal.telegramUserId(),identities.credentialScope(),"phase5-eligible-only-v1",Timestamp.from(now),Timestamp.from(now),principal.candidateId());
        jdbc.update("insert into notification_preference_revisions values(?,?,?,?,?,?)",principal.candidateId(),next,update.expectedRevision(),update.requestId(),payload,Timestamp.from(now));
        jdbc.update("update notification_activation_challenges set revoked_at=? where candidate_id=? and consumed_at is null and revoked_at is null",Timestamp.from(now),principal.candidateId());
        rebaseline(jdbc,principal.candidateId(),"PREFERENCES_CHANGED");
        return current(principal);
    }
    @Transactional(timeout=15) public View disconnect(AuthenticatedCandidate principal){
        requireNonRecovery();identities.me(principal);NotificationLocks.bound(jdbc).lockCandidate(principal.candidateId());identities.me(principal);
        if(identity.isLegacyOwner(principal.telegramUserId()))return current(principal);
        disconnectLocked(principal.candidateId(),"DISCONNECTED");return current(principal);
    }
    void disconnectLocked(long candidate,String reason){
        boolean changed=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from notification_preferences where candidate_id=? and enabled) or exists(select 1 from notification_connections where candidate_id=? and status<>'DISCONNECTED') or exists(select 1 from notification_activation_challenges where candidate_id=? and consumed_at is null and revoked_at is null)",Boolean.class,candidate,candidate,candidate));
        if(!changed)return;
        var now=Timestamp.from(clock.instant());
        jdbc.update("update notification_preferences set enabled=false,revision=revision+1,revoked_at=?,updated_at=? where candidate_id=?",now,now,candidate);
        jdbc.update("update notification_connections set status='DISCONNECTED',disconnected_at=? where candidate_id=?",now,candidate);
        jdbc.update("update notification_activation_challenges set revoked_at=? where candidate_id=? and consumed_at is null and revoked_at is null",now,candidate);
        rebaseline(jdbc,candidate,reason);
    }
    /** Caller must hold candidate; the catalogue barrier is the final lock acquired in this transaction. */
    public static void rebaseline(JdbcTemplate jdbc,long candidate,String reason){
        requireTransaction();
        if(jdbc.queryForList("select candidate_id from notification_preferences where candidate_id=? for update",Long.class,candidate).isEmpty())return;
        jdbc.queryForList("select job_id from notification_outbox where candidate_id=? and state in ('PENDING','RETRY','CLAIMED') order by job_id for update",Long.class,candidate);
        jdbc.update("update notification_outbox set state='CANCELED',reason=?,lease_until=null where candidate_id=? and state in ('PENDING','RETRY','CLAIMED')",reason,candidate);
        long baseline=jdbc.queryForObject("select change_revision from matching_catalogue_state where id=1 for update",Long.class);
        jdbc.update("update notification_preferences set generation=generation+1,baseline_revision=?,event_cursor=? where candidate_id=?",baseline,baseline,candidate);
    }
    /** Internal lifecycle hook: caller holds candidate; catalogue lock is acquired last. */
    public static void revokedSession(JdbcTemplate jdbc,long candidate,String sessionHash,Instant now){
        requireTransaction();
        int changed=jdbc.update("update notification_preferences set enabled=false,revision=revision+1,revoked_at=?,updated_at=? where candidate_id=? and consent_session_hash=? and revoked_at is null",Timestamp.from(now),Timestamp.from(now),candidate,sessionHash);
        jdbc.update("update notification_activation_challenges set revoked_at=? where candidate_id=? and session_hash=? and consumed_at is null and revoked_at is null",Timestamp.from(now),candidate,sessionHash);
        if(changed>0)rebaseline(jdbc,candidate,"CONSENT_REVOKED");
    }
    private static void requireTransaction(){if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Notification lifecycle hook requires transaction and candidate lock");}
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(java.io.IOException e){throw new IllegalStateException("Cannot encode notification preferences");}}
    private IdentityException invalid(){return new IdentityException(400,"INVALID_NOTIFICATION_PREFERENCES");}
    private IdentityException conflict(){return new IdentityException(409,"NOTIFICATION_PREFERENCES_CONFLICT");}
}
