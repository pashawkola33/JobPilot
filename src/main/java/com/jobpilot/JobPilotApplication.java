package com.jobpilot;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.browser.config.ScraperWorkerProperties;
import com.jobpilot.config.BuildInfoProperties;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.config.MaintenanceProperties;
import com.jobpilot.matching.preview.ScoreRescorePreviewProperties;
import com.jobpilot.matching.rescore.ScoreRescoreCommandProperties;
import com.jobpilot.resume.config.DocumentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({JobPilotProperties.class, CandidateProfileProperties.class,
        DocumentProperties.class, MaintenanceProperties.class, BuildInfoProperties.class,
        ScraperWorkerProperties.class, ScoreRescorePreviewProperties.class,
        ScoreRescoreCommandProperties.class})
public class JobPilotApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(JobPilotApplication.class, args);
        String commandMode = context.getEnvironment().getProperty(
                "jobpilot.score-rescore-command.mode", "OFF");
        if (!"OFF".equalsIgnoreCase(commandMode)) context.close();
    }
}
