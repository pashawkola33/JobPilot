package com.jobpilot.identity;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.config.JobPilotProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class IdentityConfigurationGuard implements SmartInitializingSingleton {
    private final IdentityProperties identity;
    private final JobPilotProperties properties;
    private final CandidateProfileProperties candidate;
    private final JdbcTemplate jdbc;
    public IdentityConfigurationGuard(IdentityProperties identity,JobPilotProperties properties,
            CandidateProfileProperties candidate,JdbcTemplate jdbc) {
        this.identity=identity;this.properties=properties;this.candidate=candidate;this.jdbc=jdbc;
    }
    @Override public void afterSingletonsInstantiated() {
        boolean legacy=properties.miniApp().enabled() || properties.telegram().enabled() || properties.telegram().commandsEnabled();
        if (!legacy && !identity.enabled()) return;
        Long owner=identity.legacyOwnerTelegramUserId();
        if (owner==null || !"default".equals(candidate.candidateKey())
                || properties.miniApp().enabled() && !properties.miniApp().allows(owner)
                || properties.telegram().commandsEnabled()
                    && (!Long.toString(owner).equals(properties.telegram().allowedUserId()) || !Long.toString(owner).equals(properties.telegram().allowedChatId()))) {
            throw new IllegalArgumentException("Legacy workflows require the explicit owner, default candidate, and matching private authorization");
        }
        var bound=jdbc.queryForList("select t.telegram_user_id from telegram_identities t join candidates c on c.id=t.candidate_id where c.stable_key='default'",Long.class);
        if (!bound.isEmpty() && !bound.getFirst().equals(owner)) throw new IllegalArgumentException("Configured legacy owner conflicts with persisted ownership");
    }
}
