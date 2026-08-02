package com.jobpilot.jobs.service;

import com.jobpilot.matching.ScoreBand;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.EarlyCareerEligibility;
import com.jobpilot.jobs.domain.WorkplaceType;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchLog;
import com.jobpilot.sources.SourceFetchLogRepository;
import com.jobpilot.telegram.TelegramNotifier;
import java.time.Clock;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class JobIngestionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobIngestionService.class);
    private final List<JobSource> sources;
    private final JobRelevanceFilter relevance;
    private final JobProcessor processor;
    private final LocationEligibilityService eligibility;
    private final EarlyCareerEligibilityService earlyCareer;
    private final SourceFetchLogRepository logs;
    private final TelegramNotifier telegram;
    private final Clock clock;

    @Autowired
    public JobIngestionService(List<JobSource> sources, JobRelevanceFilter relevance,
                               JobProcessor processor, LocationEligibilityService eligibility,
                               EarlyCareerEligibilityService earlyCareer,
                               SourceFetchLogRepository logs, TelegramNotifier telegram, Clock clock) {
        this.sources = List.copyOf(sources);
        this.relevance = relevance;
        this.processor = processor;
        this.eligibility = eligibility;
        this.earlyCareer = earlyCareer;
        this.logs = logs;
        this.telegram = telegram;
        this.clock = clock;
    }

    public JobIngestionService(List<JobSource> sources, JobRelevanceFilter relevance,
                               JobProcessor processor, SourceFetchLogRepository logs,
                               TelegramNotifier telegram, Clock clock) {
        this(sources, relevance, processor, null, null, logs, telegram, clock);
    }

    public JobIngestionService(List<JobSource> sources, JobRelevanceFilter relevance,
                               JobProcessor processor, LocationEligibilityService eligibility,
                               SourceFetchLogRepository logs, TelegramNotifier telegram, Clock clock) {
        this(sources, relevance, processor, eligibility, new EarlyCareerEligibilityService(),
                logs, telegram, clock);
    }

    public JobIngestionReport fetchAllSources() {
        ReportAccumulator report = new ReportAccumulator();
        for (JobSource source : sources) fetchOneSource(source, report);
        JobIngestionReport completed = report.toReport();
        LOGGER.info("Live vacancy smoke report: fetched={}, uniqueRaw={}, bucharest={}, "
                        + "remoteRomania={}, remoteUnknown={}, rejectedRemote={}, "
                        + "rejectedOnsiteHybrid={}, earlyCareerEligible={}, earlyCareerUnknown={}, "
                        + "rejectedSeniority={}, finalEligible={}, eligibleTenants={}",
                completed.totalVacanciesFetched(),
                completed.totalUniqueVacanciesBeforeEligibilityFiltering(),
                completed.bucharestLocalVacancies(),
                completed.remoteVacanciesEligibleFromRomania(),
                completed.remoteEligibilityUnknown(),
                completed.rejectedByGeographicRestriction(),
                completed.rejectedOnsiteOrHybridOutsideBucharest(),
                completed.earlyCareerEligibleVacancies(),
                completed.earlyCareerEligibilityUnknown(),
                completed.rejectedBySeniorityOrExperience(),
                completed.finalUniqueEligibleVacancies(),
                completed.eligibleTenantsByProvider());
        return completed;
    }

    void fetchOneSource(JobSource source) {
        fetchOneSource(source, new ReportAccumulator());
    }

    private void fetchOneSource(JobSource source, ReportAccumulator report) {
        SourceFetchLog log = logs.save(new SourceFetchLog(source.getSourceName(), clock.instant()));
        int fetched = 0;
        int saved = 0;
        try {
            var rawJobs = source.fetchJobs();
            fetched = rawJobs.size();
            for (var raw : rawJobs) {
                try {
                    LocationEligibilityDecision decision = eligibility == null ? null : eligibility.evaluate(raw);
                    EarlyCareerDecision careerDecision = decision != null && decision.accepted()
                            && earlyCareer != null ? earlyCareer.evaluate(raw) : null;
                    report.record(raw, decision, careerDecision);
                    if (decision != null && !decision.accepted()
                            || careerDecision != null && !careerDecision.accepted()
                            || !relevance.isRelevant(raw)) continue;
                    JobProcessingResult result = decision == null ? processor.process(raw)
                            : careerDecision == null ? processor.process(raw, decision)
                            : processor.process(raw, decision, careerDecision);
                    if (result.accepted() && result.newlyCreated()) {
                        saved++;
                        if (result.score() != null && result.score().band() == ScoreBand.EXCELLENT_MATCH) {
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

    private static final class ReportAccumulator {
        private int fetched;
        private int bucharest;
        private int remoteRomania;
        private int unknown;
        private int rejectedRemote;
        private int rejectedLocal;
        private int earlyEligible;
        private int earlyUnknown;
        private int rejectedSeniority;
        private final Set<String> uniqueRaw = new LinkedHashSet<>();
        private final Set<String> uniqueEligible = new LinkedHashSet<>();
        private final Map<String, Set<String>> tenants = new LinkedHashMap<>();

        private void record(com.jobpilot.jobs.domain.RawJob raw, LocationEligibilityDecision decision,
                            EarlyCareerDecision careerDecision) {
            fetched++;
            String key = key(raw);
            if (!uniqueRaw.add(key) || decision == null) return;
            switch (decision.locationEligibility()) {
                case BUCHAREST_LOCAL -> bucharest++;
                case REMOTE_ROMANIA_ELIGIBLE -> remoteRomania++;
                case REMOTE_ELIGIBILITY_UNKNOWN -> unknown++;
                case REJECTED_LOCATION -> {
                    if (decision.workplaceType() == WorkplaceType.ONSITE
                            || decision.workplaceType() == WorkplaceType.HYBRID) rejectedLocal++;
                    else rejectedRemote++;
                }
            }
            if (decision.accepted()) {
                if (careerDecision == null
                        || careerDecision.earlyCareerEligibility() == EarlyCareerEligibility.UNKNOWN) {
                    earlyUnknown++;
                } else if (careerDecision.accepted()) {
                    earlyEligible++;
                    uniqueEligible.add(key);
                    tenants.computeIfAbsent(safe(raw.source()), ignored -> new LinkedHashSet<>())
                            .add(safe(raw.providerTenant()));
                } else {
                    rejectedSeniority++;
                }
            }
        }

        private JobIngestionReport toReport() {
            Map<String, List<String>> immutableTenants = new LinkedHashMap<>();
            tenants.forEach((provider, values) -> immutableTenants.put(provider, List.copyOf(values)));
            return new JobIngestionReport(fetched, uniqueRaw.size(), bucharest, remoteRomania,
                    unknown, rejectedRemote, rejectedLocal, earlyEligible, earlyUnknown,
                    rejectedSeniority, uniqueEligible.size(), immutableTenants);
        }

        private String key(com.jobpilot.jobs.domain.RawJob raw) {
            if (raw.url() != null && !raw.url().isBlank()) return "url:" + raw.url().strip();
            if (raw.externalId() != null && !raw.externalId().isBlank()) {
                return "external:" + safe(raw.source()) + ":" + raw.externalId().strip();
            }
            return "content:" + safe(raw.company()) + "|" + safe(raw.title()) + "|" + safe(raw.location());
        }

        private String safe(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
