package com.jobpilot.scheduling;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.common.ApplicationLifecycleGate;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobIngestionService;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.telegram.TelegramNotifier;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class JobSchedulingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobSchedulingService.class);
    private final AtomicBoolean fetchRunning = new AtomicBoolean();
    private final JobIngestionService ingestion;
    private final JobScoreRepository scores;
    private final JobRepository jobs;
    private final TelegramNotifier telegram;
    private final JobPilotProperties properties;
    private final Clock clock;
    private final ApplicationLifecycleGate lifecycle;

    @Autowired
    public JobSchedulingService(JobIngestionService ingestion, JobScoreRepository scores,
                                JobRepository jobs, TelegramNotifier telegram,
                                JobPilotProperties properties, Clock clock,
                                ApplicationLifecycleGate lifecycle) {
        this.ingestion = ingestion;
        this.scores = scores;
        this.jobs = jobs;
        this.telegram = telegram;
        this.properties = properties;
        this.clock = clock;
        this.lifecycle = lifecycle;
    }

    public JobSchedulingService(JobIngestionService ingestion, JobScoreRepository scores,
                                JobRepository jobs, TelegramNotifier telegram,
                                JobPilotProperties properties, Clock clock) {
        this(ingestion, scores, jobs, telegram, properties, clock,
                new ApplicationLifecycleGate());
    }

    @Scheduled(cron = "${jobpilot.scheduling.fetch-cron}", zone = "Europe/Bucharest")
    public void fetchJobs() {
        if (!lifecycle.acceptingWork()) return;
        if (!fetchRunning.compareAndSet(false, true)) {
            LOGGER.info("Skipping overlapping job-source fetch");
            return;
        }
        try {
            ingestion.fetchAllSources();
            expireStale();
        } finally {
            fetchRunning.set(false);
        }
    }

    @Scheduled(cron = "${jobpilot.scheduling.digest-cron}", zone = "Europe/Bucharest")
    public void dailyDigest() {
        if (!lifecycle.acceptingWork()) return;
        try {
            telegram.sendGoodMatchDigest(scores.findDigest(ScoreBand.GOOD_MATCH,
                    clock.instant().minus(Duration.ofDays(1)), PageRequest.of(0, 20)));
        } catch (RuntimeException exception) {
            LOGGER.warn("Daily digest delivery failed: {}", exception.getMessage());
        }
    }

    // The transaction lives on JobRepository.expireStale; an annotation here would be
    // ignored anyway because fetchJobs() calls this method without going through the proxy.
    public int expireStale() {
        return jobs.expireStale(clock.instant().minus(Duration.ofDays(properties.scheduling().staleDays())));
    }
}
