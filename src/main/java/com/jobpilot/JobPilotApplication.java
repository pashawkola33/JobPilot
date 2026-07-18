package com.jobpilot;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.config.JobPilotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({JobPilotProperties.class, CandidateProfileProperties.class})
public class JobPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobPilotApplication.class, args);
    }
}
