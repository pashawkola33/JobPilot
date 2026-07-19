package com.jobpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jobpilot.build")
public record BuildInfoProperties(String version, String commit) {
    public BuildInfoProperties {
        version = safeToken(version);
        commit = safeToken(commit);
    }

    private static String safeToken(String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.strip();
        if (!normalized.matches("[A-Za-z0-9._+-]{1,100}")) {
            throw new IllegalArgumentException("Build identifiers contain unsafe characters");
        }
        return normalized;
    }
}
