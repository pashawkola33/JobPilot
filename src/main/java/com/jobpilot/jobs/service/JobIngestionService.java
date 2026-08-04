package com.jobpilot.jobs.service;

import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.LocationEligibility;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RelevanceDecision;
import com.jobpilot.jobs.domain.ScreeningDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.domain.WorkplaceType;
import com.jobpilot.matching.ScoreBand;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.SourceFetchFailureCategory;
import com.jobpilot.sources.SourceFetchLogHandle;
import com.jobpilot.sources.SourceFetchLogLifecycleService;
import com.jobpilot.sources.SourceFetchLogTerminalOutcome;
import com.jobpilot.sources.SourceFetchLogTerminalizationException;
import com.jobpilot.sources.health.IngestionRunContext;
import com.jobpilot.sources.health.TenantAttemptStatus;
import com.jobpilot.telegram.TelegramNotifier;
import com.jobpilot.telegram.review.TelegramReviewNotifier;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class JobIngestionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(JobIngestionService.class);
    private final List<JobSource> sources;
    private final JobRelevanceFilter relevance;
    private final JobProcessor processor;
    private final LocationEligibilityService eligibility;
    private final EarlyCareerEligibilityService earlyCareer;
    private final SourceFetchLogLifecycleService lifecycle;
    private final TelegramNotifier telegram;
    private final TelegramReviewNotifier reviewNotifier;
    private final Clock clock;

    @Autowired
    public JobIngestionService(List<JobSource> sources, JobRelevanceFilter relevance,
                               JobProcessor processor, LocationEligibilityService eligibility,
                               EarlyCareerEligibilityService earlyCareer,
                               SourceFetchLogLifecycleService lifecycle, TelegramNotifier telegram,
                               TelegramReviewNotifier reviewNotifier, Clock clock) {
        this.sources = List.copyOf(sources);
        this.relevance = relevance;
        this.processor = processor;
        this.eligibility = eligibility;
        this.earlyCareer = earlyCareer;
        this.lifecycle = lifecycle;
        this.telegram = telegram;
        this.reviewNotifier = reviewNotifier;
        this.clock = clock;
    }

    public JobIngestionService(List<JobSource> sources, JobRelevanceFilter relevance,
                               JobProcessor processor, LocationEligibilityService eligibility,
                               EarlyCareerEligibilityService earlyCareer,
                               SourceFetchLogLifecycleService lifecycle, TelegramNotifier telegram, Clock clock) {
        this(sources, relevance, processor, eligibility, earlyCareer, lifecycle, telegram, null, clock);
    }

    public JobIngestionService(List<JobSource> sources, JobRelevanceFilter relevance,
                               JobProcessor processor, SourceFetchLogLifecycleService lifecycle,
                               TelegramNotifier telegram, Clock clock) {
        this(sources, relevance, processor, null, null, lifecycle, telegram, null, clock);
    }

    public JobIngestionService(List<JobSource> sources, JobRelevanceFilter relevance,
                               JobProcessor processor, LocationEligibilityService eligibility,
                               SourceFetchLogLifecycleService lifecycle, TelegramNotifier telegram, Clock clock) {
        this(sources, relevance, processor, eligibility, new EarlyCareerEligibilityService(),
                lifecycle, telegram, null, clock);
    }

    public JobIngestionReport fetchAllSources() {
        ReportAccumulator report = new ReportAccumulator();
        // One run id correlates the aggregate source logs, every tenant attempt row,
        // and the summary lines below. It is diagnostic only and never a job identity.
        IngestionRunContext run = IngestionRunContext.open();
        JobIngestionReport completed;
        try {
            for (JobSource source : sources) fetchOneSource(source, report);
            completed = report.toReport();
            logSourceHealthSummary(run);
            notifyReviewQueue(report);
        } finally {
            IngestionRunContext.clear();
        }
        LOGGER.info("Vacancy ingestion report: runId={}, fetched={}, uniqueRaw={}, bucharest={}, "
                        + "remoteRomania={}, remoteUnknown={}, rejectedRemote={}, "
                        + "rejectedOnsiteHybrid={}, earlyCareerEligible={}, earlyCareerUnknown={}, "
                        + "rejectedSeniority={}, locationCareerEligible={}, relevanceMatch={}, "
                        + "relevanceReview={}, rejectedByRelevance={}, finalMatch={}, finalReview={}, "
                        + "finalReject={}, duplicateRaw={}, existingUnchanged={}, persistedNew={}, "
                        + "updated={}, matchTenants={}, reviewTenants={}",
                run.runId(),
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
                completed.locationAndCareerEligibleVacancies(),
                completed.relevanceMatchVacancies(), completed.relevanceReviewVacancies(),
                completed.rejectedByRelevance(), completed.finalMatchVacancies(),
                completed.finalReviewVacancies(), completed.finalRejectVacancies(),
                completed.duplicateRawVacancies(), completed.existingUnchangedVacancies(),
                completed.persistedNewVacancies(), completed.updatedVacancies(),
                completed.finalMatchTenantsByProvider(), completed.finalReviewTenantsByProvider());
        return completed;
    }

    /** Compact per-tenant reliability roll-up for the run that just finished. */
    private void logSourceHealthSummary(IngestionRunContext run) {
        LOGGER.info("Source health summary: runId={}, tenantAttempts={}, success={}, "
                        + "emptySuccess={}, failed={}, failuresByCategory={}",
                run.runId(), run.totalAttempts(),
                run.count(TenantAttemptStatus.SUCCESS),
                run.count(TenantAttemptStatus.EMPTY_SUCCESS),
                run.count(TenantAttemptStatus.FAILURE),
                run.failuresByCategory());
    }

    void fetchOneSource(JobSource source) {
        fetchOneSource(source, new ReportAccumulator());
    }

    /**
     * Lifecycle boundary for one source: the row is opened before any work and is moved to a
     * terminal status on every path the JVM can still observe. Throwable is caught here and
     * only here, solely so an Error still gets a best-effort terminal write before it is
     * rethrown untouched.
     */
    private void fetchOneSource(JobSource source, ReportAccumulator report) {
        SourceFetchLogHandle handle = lifecycle.begin(source.getSourceName(),
                IngestionRunContext.currentRunId(), clock.instant());
        SourceCounters counters = new SourceCounters();
        try {
            runSource(source, report, counters);
        } catch (RuntimeException failure) {
            // An interrupt reaches us as a RuntimeException with the flag still set, because
            // the HTTP client re-asserts it before translating.
            boolean interrupted = Thread.currentThread().isInterrupted();
            finalizeFailure(handle, interrupted
                    ? SourceFetchFailureCategory.PROCESS_INTERRUPTED
                    : SourceFetchFailureCategory.SOURCE_FAILURE, failure);
            LOGGER.warn("Job source {} failed; remaining sources will continue: {}",
                    source.getSourceName(), failure.getClass().getSimpleName());
            return;
        } catch (Error error) {
            // Best effort only, and never swallowed.
            try {
                lifecycle.fail(handle, SourceFetchFailureCategory.UNCAUGHT_ERROR, error,
                        clock.instant());
            } catch (RuntimeException finalizationFailure) {
                error.addSuppressed(finalizationFailure);
            }
            throw error;
        }
        requireFinalized(handle, lifecycle.succeed(handle, counters.fetched, counters.saved,
                clock.instant()));
    }

    /**
     * Moves a already-failing source to FAILED without ever masking why it failed: a
     * finalization problem is attached to the original exception as suppressed.
     */
    private void finalizeFailure(SourceFetchLogHandle handle,
                                 SourceFetchFailureCategory category, Throwable failure) {
        SourceFetchLogTerminalOutcome outcome;
        try {
            outcome = lifecycle.fail(handle, category, failure, clock.instant());
        } catch (RuntimeException finalizationFailure) {
            failure.addSuppressed(finalizationFailure);
            return;
        }
        if (!outcome.finalized()) {
            failure.addSuppressed(new SourceFetchLogTerminalizationException(
                    outcome, handle.id(), handle.sourceName()));
        }
    }

    /** A source is only reported as finished when its row actually reached a terminal state. */
    private void requireFinalized(SourceFetchLogHandle handle,
                                  SourceFetchLogTerminalOutcome outcome) {
        if (outcome.finalized()) return;
        LOGGER.error("Source fetch log {} for source {} did not reach a terminal status: {}",
                handle.id(), handle.sourceName(), outcome);
        throw new SourceFetchLogTerminalizationException(outcome, handle.id(), handle.sourceName());
    }

    private void runSource(JobSource source, ReportAccumulator report, SourceCounters counters) {
        long screeningStartedNanos = System.nanoTime();
        {
            List<RawJob> rawJobs = source.fetchJobs();
            counters.fetched = rawJobs.size();
            for (RawJob raw : rawJobs) {
                // Stop promptly on a graceful shutdown rather than working through the rest.
                if (Thread.currentThread().isInterrupted()) {
                    throw new SourceInterruptedException();
                }
                try {
                    if (!report.recordFetched(raw)) continue;
                    if (eligibility == null || earlyCareer == null) {
                        JobProcessingResult result = processor.process(raw);
                        report.recordPersistence(result);
                        if (result.newlyCreated()) counters.saved++;
                        continue;
                    }
                    LocationEligibilityDecision locationDecision = eligibility.evaluate(raw);
                    report.recordLocation(locationDecision);
                    if (locationDecision.disposition() == ScreeningDisposition.REJECT) {
                        report.recordFinalReject();
                        report.recordPersistence(processor.reconcileRejected(
                                raw, locationDecision, null, null));
                        continue;
                    }

                    EarlyCareerDecision careerDecision = earlyCareer.evaluate(raw);
                    report.recordCareer(careerDecision);
                    if (careerDecision.disposition() == ScreeningDisposition.REJECT) {
                        report.recordFinalReject();
                        report.recordPersistence(processor.reconcileRejected(
                                raw, locationDecision, careerDecision, null));
                        continue;
                    }

                    report.recordLocationAndCareerEligible(raw);
                    RelevanceDecision relevanceDecision = relevance.evaluate(raw);
                    report.recordRelevance(relevanceDecision);
                    ScreeningDecision screening = ScreeningDecision.of(
                            locationDecision, careerDecision, relevanceDecision);
                    report.recordFinal(raw, screening);
                    if (screening.disposition() == ScreeningDisposition.REJECT) {
                        report.recordPersistence(processor.reconcileRejected(
                                raw, locationDecision, careerDecision, relevanceDecision));
                        continue;
                    }

                    JobProcessingResult result = processor.process(raw, locationDecision,
                            careerDecision, relevanceDecision);
                    report.recordPersistence(result);
                    if (result.newlyCreated()) {
                        counters.saved++;
                        if (result.finalDisposition() == ScreeningDisposition.MATCH
                                && result.score() != null
                                && result.score().band() == ScoreBand.EXCELLENT_MATCH) {
                            notifyExcellent(result);
                        }
                    }
                } catch (RuntimeException exception) {
                    // Per-job isolation is unchanged, except that an interrupt is never
                    // swallowed: it must reach the lifecycle boundary.
                    if (Thread.currentThread().isInterrupted()) throw exception;
                    LOGGER.warn("Rejected one job from source {}: {}", source.getSourceName(),
                            exception.getClass().getSimpleName());
                }
            }
            // Bounded: run id, source, job count and elapsed milliseconds only. No title,
            // description, URL, candidate data, or any secret-bearing field.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Source screening timing: runId={}, source={}, jobs={}, elapsedMs={}",
                        IngestionRunContext.currentRunId(), source.getSourceName(),
                        counters.fetched,
                        (System.nanoTime() - screeningStartedNanos) / 1_000_000L);
            }
        }
    }

    /** Mutable counters for one source, scoped to the lifecycle boundary. */
    private static final class SourceCounters {
        private int fetched;
        private int saved;
    }

    /** Raised when a graceful shutdown interrupts a source part-way through its vacancies. */
    static final class SourceInterruptedException extends RuntimeException {
        SourceInterruptedException() {
            super("Source processing was interrupted");
        }
    }

    /** Best effort. The review push runs after persistence and can never fail the run. */
    private void notifyReviewQueue(ReportAccumulator report) {
        if (reviewNotifier == null) return;
        try {
            reviewNotifier.notifyIngestion(report.newMatchJobIds, report.newReviewJobIds);
        } catch (RuntimeException notificationFailure) {
            LOGGER.warn("Telegram review notification failed: {}",
                    notificationFailure.getClass().getSimpleName());
        }
    }

    private void notifyExcellent(JobProcessingResult result) {
        try {
            telegram.notifyExcellent(result.job(), result.score());
        } catch (RuntimeException notificationFailure) {
            LOGGER.warn("Telegram notification failed for job {}: {}", result.job().getId(),
                    notificationFailure.getClass().getSimpleName());
        }
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
        private int relevanceMatch;
        private int relevanceReview;
        private int rejectedRelevance;
        private int finalReject;
        private int duplicateRaw;
        private int existingUnchanged;
        private int persistedNew;
        private int updated;
        private final Set<String> uniqueRaw = new LinkedHashSet<>();
        private final Set<String> locationCareerEligible = new LinkedHashSet<>();
        private final Set<String> finalMatches = new LinkedHashSet<>();
        private final Set<String> finalReviews = new LinkedHashSet<>();
        private final Map<String, Set<String>> locationCareerTenants = new LinkedHashMap<>();
        private final Map<String, Set<String>> matchTenants = new LinkedHashMap<>();
        private final Map<String, Set<String>> reviewTenants = new LinkedHashMap<>();
        private final Set<Long> newMatchJobIds = new LinkedHashSet<>();
        private final Set<Long> newReviewJobIds = new LinkedHashSet<>();

        private boolean recordFetched(RawJob raw) {
            fetched++;
            if (uniqueRaw.add(RawJobIdentity.key(raw))) return true;
            duplicateRaw++;
            return false;
        }

        private void recordLocation(LocationEligibilityDecision location) {
            switch (location.locationEligibility()) {
                case BUCHAREST_LOCAL -> bucharest++;
                case REMOTE_ROMANIA_ELIGIBLE -> remoteRomania++;
                case REMOTE_ELIGIBILITY_UNKNOWN -> unknown++;
                case REJECTED_LOCATION -> {
                    if (location.workplaceType() == WorkplaceType.ONSITE
                            || location.workplaceType() == WorkplaceType.HYBRID) rejectedLocal++;
                    else rejectedRemote++;
                }
            }
        }

        private void recordCareer(EarlyCareerDecision career) {
            switch (career.disposition()) {
                case MATCH -> earlyEligible++;
                case REVIEW -> earlyUnknown++;
                case REJECT -> rejectedSeniority++;
            }
        }

        private void recordLocationAndCareerEligible(RawJob raw) {
            String key = RawJobIdentity.key(raw);
            locationCareerEligible.add(key);
            tenant(locationCareerTenants, raw);
        }

        private void recordRelevance(RelevanceDecision relevance) {
            switch (relevance.disposition()) {
                case MATCH -> relevanceMatch++;
                case REVIEW -> relevanceReview++;
                case REJECT -> rejectedRelevance++;
            }
        }

        private void recordFinal(RawJob raw, ScreeningDecision screening) {
            String key = RawJobIdentity.key(raw);
            if (screening.disposition() == ScreeningDisposition.MATCH) {
                finalMatches.add(key);
                tenant(matchTenants, raw);
            } else if (screening.disposition() == ScreeningDisposition.REVIEW) {
                finalReviews.add(key);
                tenant(reviewTenants, raw);
            } else {
                recordFinalReject();
            }
        }

        private void recordFinalReject() {
            finalReject++;
        }

        private void recordPersistence(JobProcessingResult result) {
            switch (result.persistenceOutcome()) {
                case CREATED -> persistedNew++;
                case UPDATED -> updated++;
                case UNCHANGED -> existingUnchanged++;
                case NOT_PERSISTED -> { }
            }
            recordNotifiable(result);
        }

        /**
         * Only vacancies created by this run are eligible for a Telegram push, so enabling
         * the bot can never replay the existing backlog.
         */
        private void recordNotifiable(JobProcessingResult result) {
            if (!result.newlyCreated() || result.job() == null || result.job().getId() == null) return;
            switch (result.finalDisposition()) {
                case MATCH -> newMatchJobIds.add(result.job().getId());
                case REVIEW -> newReviewJobIds.add(result.job().getId());
                case REJECT -> { }
            }
        }

        private JobIngestionReport toReport() {
            return new JobIngestionReport(fetched, uniqueRaw.size(), bucharest, remoteRomania,
                    unknown, rejectedRemote, rejectedLocal, earlyEligible, earlyUnknown,
                    rejectedSeniority, locationCareerEligible.size(), relevanceMatch,
                    relevanceReview, rejectedRelevance, finalMatches.size(), finalReviews.size(),
                    finalReject, duplicateRaw, existingUnchanged, persistedNew, updated,
                    mapped(locationCareerTenants),
                    mapped(matchTenants), mapped(reviewTenants));
        }

        private void tenant(Map<String, Set<String>> target, RawJob raw) {
            target.computeIfAbsent(safe(raw.source()), ignored -> new LinkedHashSet<>())
                    .add(safe(raw.providerTenant()));
        }

        private Map<String, List<String>> mapped(Map<String, Set<String>> source) {
            Map<String, List<String>> result = new LinkedHashMap<>();
            source.forEach((provider, tenants) -> result.put(provider, List.copyOf(tenants)));
            return result;
        }

        private String safe(String value) {
            return value == null ? "" : value.strip();
        }
    }
}
