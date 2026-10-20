package com.jobpilot.personalizedmatching;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import java.util.Arrays;
@Component
@EnableConfigurationProperties(MatchingProperties.class)
public class MatchingWorker {
    private final MatchingProperties settings;private final MatchingCatalogue catalogue;private final MatchingWorkService work;private final Environment env;private final com.jobpilot.common.ApplicationLifecycleGate lifecycle;
    public MatchingWorker(MatchingProperties settings,MatchingCatalogue catalogue,MatchingWorkService work,Environment env,com.jobpilot.common.ApplicationLifecycleGate lifecycle){this.settings=settings;this.catalogue=catalogue;this.work=work;this.env=env;this.lifecycle=lifecycle;}
    @Scheduled(fixedDelayString="${jobpilot.personalized-matching.tick-ms:1000}")
    public void tick() {
        if(!lifecycle.acceptingWork()||!settings.enabled()||Arrays.asList(env.getActiveProfiles()).contains("recovery")||!env.getProperty("jobpilot.scheduled-tasks-enabled",Boolean.class,true))return;
        catalogue.bootstrap();work.initializeExistingProfiles();work.runOne();
    }
}
