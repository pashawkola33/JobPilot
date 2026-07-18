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

    public record Telegram(String botToken, String channelId) {
        public boolean enabled() {
            return botToken != null && !botToken.isBlank();
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
