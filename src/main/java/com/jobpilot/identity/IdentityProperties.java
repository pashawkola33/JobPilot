package com.jobpilot.identity;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jobpilot.identity")
public record IdentityProperties(boolean enabled, boolean registrationEnabled,
        Long legacyOwnerTelegramUserId, List<Long> betaAllowedUserIds,
        Duration sessionLifetime, String credentialEpoch) {
    public IdentityProperties {
        betaAllowedUserIds = betaAllowedUserIds == null ? List.of() : List.copyOf(betaAllowedUserIds);
        sessionLifetime = sessionLifetime == null ? Duration.ofMinutes(15) : sessionLifetime;
        credentialEpoch = credentialEpoch == null ? "1" : credentialEpoch;
        if (legacyOwnerTelegramUserId != null && legacyOwnerTelegramUserId <= 0
                || betaAllowedUserIds.size() > 1000 || betaAllowedUserIds.stream().anyMatch(x -> x == null || x <= 0)
                || sessionLifetime.isNegative() || sessionLifetime.isZero() || sessionLifetime.compareTo(Duration.ofMinutes(15)) > 0
                || !credentialEpoch.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Identity configuration is outside its safe bounds");
        }
        if (enabled && legacyOwnerTelegramUserId == null) {
            throw new IllegalArgumentException("Identity API requires an explicit legacy owner Telegram user id");
        }
    }
    public boolean isLegacyOwner(long userId) {
        return legacyOwnerTelegramUserId != null && legacyOwnerTelegramUserId == userId;
    }
    public boolean admits(long userId) {
        return isLegacyOwner(userId) || registrationEnabled && betaAllowedUserIds.contains(userId);
    }
}
