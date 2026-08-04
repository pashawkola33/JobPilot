package com.jobpilot.candidate.service;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "jobpilot.score-rescore-command.mode", havingValue = "OFF",
        matchIfMissing = true)
public class CandidateProfileBootstrapRunner implements ApplicationRunner {
    private final CandidateProfileBootstrapService bootstrapService;
    private final CandidateProfileProperties profile;

    public CandidateProfileBootstrapRunner(CandidateProfileBootstrapService bootstrapService,
                                           CandidateProfileProperties profile) {
        this.bootstrapService = bootstrapService;
        this.profile = profile;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapService.bootstrap(profile);
    }
}
