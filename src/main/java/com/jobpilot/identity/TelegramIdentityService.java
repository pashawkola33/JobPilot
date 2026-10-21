package com.jobpilot.identity;

import com.jobpilot.common.Hashing;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.miniapp.auth.TelegramInitDataValidator;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TelegramIdentityService {
    private static final Duration LAUNCH_AGE = Duration.ofMinutes(5);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private final JdbcTemplate jdbc;
    private final IdentityProperties settings;
    private final TelegramInitDataValidator validator;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment notificationEnvironment=new org.springframework.core.env.StandardEnvironment();
    private final String credentialScope;
    private final String replayScope;
    private final SecureRandom random = new SecureRandom();
    private long requestWindow = Long.MIN_VALUE;
    private int requests;

    public TelegramIdentityService(JdbcTemplate jdbc, IdentityProperties settings,
            TelegramInitDataValidator validator, JobPilotProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.validator = validator;
        this.clock = clock;
        this.replayScope = Hashing.sha256(properties.telegram().botToken());
        this.credentialScope = Hashing.sha256(replayScope + "\0" + settings.credentialEpoch());
        if (settings.enabled() && properties.telegram().botToken().isBlank()) {
            throw new IllegalArgumentException("Identity API requires a Telegram bot token");
        }
    }

    @Transactional
    public Session exchange(String initData) {
        requireEnabled();
        limitRequests();
        var verified = validator.validate(initData, LAUNCH_AGE, CLOCK_SKEW);
        if (!verified.valid()) throw new IdentityException(401, "INVALID_AUTH");
        long user = verified.userId();
        if (!settings.admits(user)) throw new IdentityException(403, "FORBIDDEN");
        // Serialize registration per Telegram identity across processes without creating a placeholder candidate.
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, user);
        Instant now = clock.instant();
        String fingerprint = Hashing.sha256(replayScope + "\0" + verified.fingerprint());
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from telegram_auth_exchanges where fingerprint=?)", Boolean.class, fingerprint))) {
            throw new IdentityException(409, "AUTH_LAUNCH_REPLAYED");
        }
        if (jdbc.queryForObject("select count(*) from auth_sessions where telegram_user_id=? and created_at>=?", Long.class,
                user, Timestamp.from(now.minusSeconds(60))) >= 60) throw new IdentityException(429, "AUTH_RATE_LIMITED");
        Long candidate = candidateFor(user);
        if (settings.isLegacyOwner(user)) {
            var defaults = jdbc.queryForList("select id from candidates where stable_key='default' for update", Long.class);
            if (defaults.size() != 1 || candidate != null && !candidate.equals(defaults.getFirst())) {
                throw new IdentityException(403, "LEGACY_OWNER_CONFLICT");
            }
            Long mapped = jdbc.query("select telegram_user_id from telegram_identities where candidate_id=?", (r,n)->r.getLong(1), defaults.getFirst()).stream().findFirst().orElse(null);
            if (mapped != null && mapped != user) throw new IdentityException(403, "LEGACY_OWNER_CONFLICT");
            candidate = defaults.getFirst();
        } else if (candidate != null && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from candidates where id=? and stable_key='default')", Boolean.class, candidate))) {
            throw new IdentityException(403, "LEGACY_OWNER_CONFLICT");
        }
        if (candidateFor(user) == null) {
            if (candidate == null) candidate = jdbc.queryForObject("insert into candidates(stable_key,created_at,updated_at) values (?,?,?) returning id", Long.class,
                    "account-" + UUID.randomUUID(), Timestamp.from(now), Timestamp.from(now));
            jdbc.update("insert into telegram_identities(telegram_user_id,candidate_id,created_at) values(?,?,?)", user, candidate, Timestamp.from(now));
        }
        Instant expires = now.plus(settings.sessionLifetime());
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("insert into auth_sessions(token_hash,telegram_user_id,candidate_id,credential_scope,created_at,expires_at) values(?,?,?,?,?,?)",
                Hashing.sha256(token), user, candidate, credentialScope, Timestamp.from(now), Timestamp.from(expires));
        // Retain a spent launch through its entire acceptance window, including future skew.
        jdbc.update("insert into telegram_auth_exchanges(fingerprint,telegram_user_id,candidate_id,consumed_at,expires_at) values(?,?,?,?,?)",
                fingerprint, user, candidate, Timestamp.from(now), Timestamp.from(verified.issuedAt().plus(LAUNCH_AGE).plus(CLOCK_SKEW)));
        return new Session(token, expires);
    }

    private Long candidateFor(long user) {
        return jdbc.query("select candidate_id from telegram_identities where telegram_user_id=?", (r,n)->r.getLong(1), user).stream().findFirst().orElse(null);
    }
    public AuthenticatedCandidate authenticate(String bearerToken) {
        requireEnabled();
        if (bearerToken == null || !bearerToken.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
        return activeSession(Hashing.sha256(bearerToken));
    }
    private AuthenticatedCandidate activeSession(String hash) {
        var sessions = jdbc.query("select candidate_id,telegram_user_id,expires_at from auth_sessions where token_hash=? and credential_scope=? and revoked_at is null and expires_at>?",
                (r,n)->new AuthenticatedCandidate(r.getLong(1),r.getLong(2),r.getTimestamp(3).toInstant(),hash), hash, credentialScope, Timestamp.from(clock.instant()));
        if (sessions.size()!=1 || !settings.admits(sessions.getFirst().telegramUserId())) throw unauthorized();
        return sessions.getFirst();
    }
    public Account me(AuthenticatedCandidate principal) {
        requireEnabled();
        if (principal == null) throw unauthorized();
        var current = activeSession(principal.sessionHash());
        if (!Objects.equals(current,principal)) throw unauthorized();
        boolean complete=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from search_profile_state s join search_profile_revisions r on r.candidate_id=s.candidate_id and r.revision=s.active_revision where s.candidate_id=? and r.completed=true)",Boolean.class,current.candidateId()));
        return new Account(Long.toString(current.candidateId()), complete?"READY":"ONBOARDING_REQUIRED", !complete,
                settings.isLegacyOwner(current.telegramUserId()), current.expiresAt());
    }
    @Transactional(timeout=15)
    public void revoke(AuthenticatedCandidate principal) {
        me(principal);
        com.jobpilot.notifications.NotificationLocks.bound(jdbc).lockCandidate(principal.candidateId());
        me(principal);
        jdbc.update("update auth_sessions set revoked_at=? where token_hash=?", Timestamp.from(clock.instant()), principal.sessionHash());
        if(!java.util.Arrays.asList(notificationEnvironment.getActiveProfiles()).contains("recovery"))
            com.jobpilot.notifications.NotificationSettingsService.revokedSession(jdbc,principal.candidateId(),principal.sessionHash(),clock.instant());
    }
    public String credentialScope() { return credentialScope; }
    private void requireEnabled() {
        if (!settings.enabled()) throw new IdentityException(503, "IDENTITY_DISABLED");
    }
    private IdentityException unauthorized() { return new IdentityException(401, "UNAUTHENTICATED"); }
    private synchronized void limitRequests() {
        long window = Math.floorDiv(clock.instant().getEpochSecond(),60);
        if (window != requestWindow) { requestWindow=window; requests=0; }
        // shortcut: bounded single-instance ingress protection; coordinate limits before adding API replicas.
        if (++requests > 120) throw new IdentityException(429,"AUTH_RATE_LIMITED");
    }
    public record Session(String accessToken, Instant expiresAt) {
        @Override public String toString() { return "Session[redacted]"; }
    }
    public record Account(String accountId, String accountState, boolean onboardingRequired,
                          boolean legacyOwner, Instant sessionExpiresAt) { }
}
