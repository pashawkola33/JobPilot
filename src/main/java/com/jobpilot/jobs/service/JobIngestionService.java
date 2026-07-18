package com.jobpilot.jobs.service;

import com.jobpilot.matching.ScoreBand;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchLog;
import com.jobpilot.sources.SourceFetchLogRepository;
import com.jobpilot.telegram.TelegramNotifier;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JobIngestionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobIngestionService.class);
    private final List<JobSource> sources;
    private final JobRelevanceFilter relevance;
    private final JobProcessor processor;
    private final SourceFetchLogRepository logs;
    private final TelegramNotifier telegram;
    private final Clock clock;

    public JobIngestionService(List<JobSource> sources, JobRelevanceFilter relevance,
                               JobProcessor processor, SourceFetchLogRepository logs,
                               TelegramNotifier telegram, Clock clock) {
        this.sources = List.copyOf(sources);
        this.relevance = relevance;
        this.processor = processor;
        this.logs = logs;
        this.telegram = telegram;
        this.clock = clock;
    }

    public void fetchAllSources() {
        for (JobSource source : sources) fetchOneSource(source);
    }

    void fetchOneSource(JobSource source) {
        SourceFetchLog log = logs.save(new SourceFetchLog(source.getSourceName(), clock.instant()));
        int fetched = 0;
        int saved = 0;
        try {
            var rawJobs = source.fetchJobs();
            fetched = rawJobs.size();
            for (var raw : rawJobs) {
                if (!relevance.isRelevant(raw)) continue;
                try {
                    JobProcessingResult result = processor.process(raw);
                    if (result.newlyCreated()) {
                        saved++;
                        if (result.score().band() == ScoreBand.EXCELLENT_MATCH) {
                            try {
                                telegram.notifyExcellent(result.job(), result.score());
                            } catch (RuntimeException notificationFailure) {
                                LOGGER.warn("Telegram notification failed for job {}: {}", result.job().getId(),
                                        notificationFailure.getClass().getSimpleName());
                            }
                        }
                    }
                } catch (RuntimeException exception) {
                    LOGGER.warn("Rejected one job from source {}: {}", source.getSourceName(),
                            exception.getClass().getSimpleName());
                }
            }
            log.succeed(fetched, saved, clock.instant());
        } catch (RuntimeException exception) {
            log.fail(exception, clock.instant());
            LOGGER.warn("Job source {} failed; remaining sources will continue: {}",
                    source.getSourceName(), exception.getClass().getSimpleName());
        }
        logs.save(log);
    }
}
