package com.jobpilot.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jobpilot")
public record JobPilotProperties(
        Telegram telegram,
        Sources sources,
        Candidate candidate,
        Http http,
        ManualUrl manualUrl,
        Scheduling scheduling,
        List<String> searchTerms,
        List<String> locations) {

    public JobPilotProperties {
        telegram = telegram == null ? new Telegram("", "") : telegram;
    }

    public record Telegram(
            String botToken,
            String channelId,
            String botUsername,
            boolean commandsEnabled,
            String allowedChatId,
            String allowedUserId,
            Duration pollTimeout,
            Duration pollDelay,
            int pollLimit,
            int maxUpdateFailures,
            boolean discardPendingOnFirstStart) {
        public Telegram {
            botUsername = normalizeBotUsername(botUsername);
            pollTimeout = pollTimeout == null ? Duration.ofSeconds(25) : pollTimeout;
            pollDelay = pollDelay == null ? Duration.ofSeconds(2) : pollDelay;
            if (pollTimeout.isNegative() || pollTimeout.compareTo(Duration.ofSeconds(50)) > 0
                    || pollDelay.isNegative() || pollDelay.compareTo(Duration.ofMinutes(1)) > 0
                    || pollLimit < 1 || pollLimit > 100
                    || maxUpdateFailures < 1 || maxUpdateFailures > 20) {
                throw new IllegalArgumentException("Telegram polling limits are outside their safe bounds");
            }
            if (commandsEnabled && (blank(botToken) || !validBotUsername(botUsername)
                    || !validChatId(allowedChatId)
                    || !validUserId(allowedUserId))) {
                throw new IllegalArgumentException(
                        "Telegram commands require a bot token, bot username, and explicit chat and user authorization");
            }
        }

        public Telegram(String botToken, String channelId) {
            this(botToken, channelId, "", false, "", "", Duration.ofSeconds(25),
                    Duration.ofSeconds(2), 50, 3, true);
        }

        public boolean enabled() {
            return !blank(botToken);
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }

        private static String normalizeBotUsername(String value) {
            if (value == null) return "";
            String normalized = value.strip();
            return normalized.startsWith("@") ? normalized.substring(1) : normalized;
        }

        private static boolean validBotUsername(String value) {
            return value != null && value.matches("(?i)[a-z0-9_]{5,32}")
                    && value.toLowerCase(java.util.Locale.ROOT).endsWith("bot");
        }

        private static boolean validChatId(String value) {
            return validLong(value, "-?[1-9]\\d{0,18}");
        }

        private static boolean validUserId(String value) {
            return validLong(value, "[1-9]\\d{0,18}");
        }

        private static boolean validLong(String value, String pattern) {
            if (blank(value) || !value.matches(pattern)) return false;
            try {
                Long.parseLong(value);
                return true;
            } catch (NumberFormatException invalid) {
                return false;
            }
        }
    }

    public record Sources(
            List<String> greenhouseBoardTokens,
            List<String> leverCompanyIds) {
    }

    public record Candidate(
            String homeCountry,
            List<String> preferredLocations,
            List<String> backendSkills,
            List<String> supportingSkills,
            boolean currentStudent,
            boolean finalYearStudent,
            double commercialJavaYears) {
    }

    public record Http(Duration connectTimeout, Duration responseTimeout, int maxResponseBytes) {
    }

    public record ManualUrl(
            Duration connectTimeout,
            Duration responseTimeout,
            int maxRedirects,
            int maxResponseBytes,
            int maxTitleLength,
            int maxDescriptionLength) {
        public ManualUrl {
            if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                    || connectTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("Manual URL connect timeout must be between 1ns and 30s");
            }
            if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()
                    || responseTimeout.compareTo(Duration.ofSeconds(60)) > 0) {
                throw new IllegalArgumentException("Manual URL response timeout must be between 1ns and 60s");
            }
            if (maxRedirects < 0 || maxRedirects > 10
                    || maxResponseBytes < 1_024 || maxResponseBytes > 10 * 1_024 * 1_024
                    || maxTitleLength < 1 || maxTitleLength > 1_000
                    || maxDescriptionLength < 40 || maxDescriptionLength > 1_000_000) {
                throw new IllegalArgumentException("Manual URL limits are outside their safe bounds");
            }
        }
    }

    public record Scheduling(String fetchCron, String digestCron, int staleDays) {
    }
}
