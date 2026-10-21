package com.jobpilot.notifications;
import com.jobpilot.common.Hashing;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.identity.*;
import com.jobpilot.telegram.api.TelegramUpdate;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class NotificationActivationService {
    public record Challenge(String activationUrl,Instant expiresAt){@Override public String toString(){return "Challenge[redacted]";}}
    private final JdbcTemplate jdbc;private final TelegramIdentityService identities;private final NotificationSettingsService settings;
    private final IdentityProperties identity;private final JobPilotProperties app;private final Clock clock;private final SecureRandom random=new SecureRandom();
    public NotificationActivationService(JdbcTemplate jdbc,TelegramIdentityService identities,NotificationSettingsService settings,IdentityProperties identity,JobPilotProperties app,Clock clock){this.jdbc=jdbc;this.identities=identities;this.settings=settings;this.identity=identity;this.app=app;this.clock=clock;}
    @Transactional(timeout=15) public Challenge challenge(AuthenticatedCandidate principal){
        settings.requireMutable();identities.me(principal);if(identity.isLegacyOwner(principal.telegramUserId()))throw new IdentityException(409,"LEGACY_MANAGED");
        NotificationLocks.bound(jdbc).lockCandidate(principal.candidateId());identities.me(principal);
        if(!settings.consentValid(principal.candidateId()))throw new IdentityException(409,"CONSENT_REQUIRED");
        String bot=app.telegram().botUsername();if(bot==null||!bot.matches("[A-Za-z][A-Za-z0-9_]{4,31}"))throw new IdentityException(503,"NOTIFICATIONS_UNAVAILABLE");
        Instant now=clock.instant();
        if(jdbc.queryForObject("select count(*) from notification_activation_challenges where candidate_id=? and created_at>?",Long.class,principal.candidateId(),Timestamp.from(now.minusSeconds(600)))>=5)throw new IdentityException(429,"ACTIVATION_RATE_LIMITED");
        jdbc.update("update notification_activation_challenges set revoked_at=? where candidate_id=? and consumed_at is null and revoked_at is null",Timestamp.from(now),principal.candidateId());
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);Instant expires=now.plusSeconds(600);
        long revision=jdbc.queryForObject("select revision from notification_preferences where candidate_id=?",Long.class,principal.candidateId());
        jdbc.update("insert into notification_activation_challenges(token_hash,candidate_id,telegram_user_id,session_hash,credential_scope,preference_revision,created_at,expires_at) values(?,?,?,?,?,?,?,?)",Hashing.sha256(token),principal.candidateId(),principal.telegramUserId(),principal.sessionHash(),identities.credentialScope(),revision,Timestamp.from(now),Timestamp.from(expires));
        return new Challenge("https://t.me/"+bot+"?start=notify_"+token,expires);
    }
    public boolean consentValid(long candidate){return settings.consentValid(candidate);}
    @Transactional(timeout=15) public boolean process(TelegramUpdate update){
        if(update==null||update.message()==null)return false;var m=update.message();String text=m.text();
        boolean stop="/notifications_off".equals(text);boolean start=text!=null&&text.matches("/start notify_[A-Za-z0-9_-]{43}");
        if(!stop&&!start)return false;
        if(m.from()!=null&&identity.isLegacyOwner(m.from().id()))return false;
        try{if(stop)settings.requireNonRecovery();else settings.requireMutable();}catch(IdentityException unavailable){return true;}
        if(m.from()==null||m.chat()==null||m.from().id()<=0||m.chat().id()!=m.from().id()||!"private".equals(m.chat().type())||!Boolean.FALSE.equals(m.from().isBot())||m.forwarded()||m.date()==null||m.date()<=0||m.date()>clock.instant().plusSeconds(30).getEpochSecond()||(start&&!identity.admits(m.from().id())))return true;
        var candidates=jdbc.queryForList("select candidate_id from telegram_identities where telegram_user_id=?",Long.class,m.from().id());if(candidates.size()!=1)return true;long candidate=candidates.getFirst();
        NotificationLocks.bound(jdbc).lockCandidate(candidate);
        if(stop){settings.disconnectLocked(candidate,"NOTIFICATIONS_OFF");return true;}
        jdbc.queryForList("select candidate_id from notification_preferences where candidate_id=? for update",Long.class,candidate);
        String hash=Hashing.sha256(text.substring("/start notify_".length()));
        var rows=jdbc.queryForList("select c.created_at from notification_activation_challenges c join notification_preferences p on p.candidate_id=c.candidate_id and p.revision=c.preference_revision join auth_sessions s on s.token_hash=c.session_hash and s.candidate_id=c.candidate_id and s.telegram_user_id=c.telegram_user_id where c.token_hash=? and c.candidate_id=? and c.telegram_user_id=? and c.credential_scope=? and c.expires_at>? and c.consumed_at is null and c.revoked_at is null and s.revoked_at is null and p.enabled and p.revoked_at is null",hash,candidate,m.from().id(),identities.credentialScope(),Timestamp.from(clock.instant()));
        if(rows.size()!=1||!settings.consentValid(candidate))return true;
        Instant created=((Timestamp)rows.getFirst().get("created_at")).toInstant();if(m.date()<created.minusSeconds(30).getEpochSecond())return true;
        Timestamp now=Timestamp.from(clock.instant());
        jdbc.update("update notification_activation_challenges set consumed_at=? where token_hash=?",now,hash);
        jdbc.update("insert into notification_connections(candidate_id,telegram_user_id,chat_id,credential_scope,status,activated_at) values(?,?,?,?,'CONNECTED',?) on conflict(candidate_id) do update set telegram_user_id=excluded.telegram_user_id,chat_id=excluded.chat_id,credential_scope=excluded.credential_scope,status='CONNECTED',activated_at=excluded.activated_at,disconnected_at=null",candidate,m.from().id(),m.chat().id(),identities.credentialScope(),now);
        NotificationSettingsService.rebaseline(jdbc,candidate,"CONNECTION_ACTIVATED");return true;
    }
}
