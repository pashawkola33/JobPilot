package com.jobpilot.scheduling;

import com.jobpilot.config.JobPilotProperties;
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
import org.springframework.transaction.annotation.Transactional;

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

    public JobSchedulingService(JobIngestionService ingestion, JobScoreRepository scores,
                                JobRepository jobs, TelegramNotifier telegram,
                                JobPilotProperties properties, Clock clock) {
        this.ingestion = ingestion;
        this.scores = scores;
        this.jobs = jobs;
        this.telegram = telegram;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${jobpilot.scheduling.fetch-cron}", zone = "Europe/Bucharest")
    public void fetchJobs() {
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
        try {
            telegram.sendGoodMatchDigest(scores.findDigest(ScoreBand.GOOD_MATCH,
                    clock.instant().minus(Duration.ofDays(1)), PageRequest.of(0, 20)));
        } catch (RuntimeException exception) {
            LOGGER.warn("Daily digest delivery failed: {}", exception.getMessage());
        }
    }

    @Transactional
    public int expireStale() {
        return jobs.expireStale(clock.instant().minus(Duration.ofDays(properties.scheduling().staleDays())));
    }
}
