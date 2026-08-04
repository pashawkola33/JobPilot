package com.jobpilot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Registers every scheduled task only when the normal runtime explicitly permits it. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jobpilot.scheduled-tasks-enabled", havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfiguration {
}
