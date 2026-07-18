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

    public record Scheduling(String fetchCron, String digestCron, int staleDays) {
    }
}
