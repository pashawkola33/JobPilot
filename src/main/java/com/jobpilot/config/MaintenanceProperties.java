package com.jobpilot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jobpilot.maintenance")
public record MaintenanceProperties(
        boolean enabled,
        Duration interval,
        int maxItemsPerRun,
        Duration maxDurationPerRun,
        Duration orphanGracePeriod) {

    public MaintenanceProperties {
        interval = interval == null ? Duration.ofMinutes(15) : interval;
        maxDurationPerRun = maxDurationPerRun == null ? Duration.ofSeconds(30) : maxDurationPerRun;
        orphanGracePeriod = orphanGracePeriod == null ? Duration.ofHours(1) : orphanGracePeriod;
        if (interval.compareTo(Duration.ofMinutes(1)) < 0
                || interval.compareTo(Duration.ofDays(1)) > 0
                || maxItemsPerRun < 1 || maxItemsPerRun > 1_000
                || maxDurationPerRun.compareTo(Duration.ofSeconds(1)) < 0
                || maxDurationPerRun.compareTo(Duration.ofMinutes(5)) > 0
                || orphanGracePeriod.compareTo(Duration.ofMinutes(10)) < 0
                || orphanGracePeriod.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Maintenance limits are outside their safe bounds");
        }
    }
}
