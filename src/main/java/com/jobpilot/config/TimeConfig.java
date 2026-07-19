package com.jobpilot.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Long telegramPollDelay(JobPilotProperties properties) {
        return properties.telegram().pollDelay().toMillis();
    }

    @Bean
    Long maintenanceInterval(MaintenanceProperties properties) {
        return properties.interval().toMillis();
    }
}
