package com.jobpilot.jobs.service;

import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RelevanceDecision;
import com.jobpilot.jobs.domain.ScreeningDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.domain.WorkplaceType;
import com.jobpilot.sources.JobSource;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Read-only live registry check: fetches and classifies without scoring or persistence. */
@Service
public class LiveVacancySmokeService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveVacancySmokeService.class);
    private final List<JobSource> sources;
    private final LocationEligibilityService eligibility;
    private final EarlyCareerEligibilityService earlyCareer;
    private final JobRelevanceFilter relevance;

    public LiveVacancySmokeService(List<JobSource> sources, LocationEligibilityService eligibility,
                                   EarlyCareerEligibilityService earlyCareer,
                                   JobRelevanceFilter relevance) {
        this.sources = List.copyOf(sources);
        this.eligibility = eligibility;
        this.earlyCareer = earlyCareer;
        this.relevance = relevance;
    }

    public JobIngestionReport collect() {
        MutableReport report = new MutableReport();
        for (JobSource source : sources) {
            try {
                for (RawJob raw : source.fetchJobs()) {
                    if (!report.recordFetched(raw)) continue;
                    LocationEligibilityDecision location = eligibility.evaluate(raw);
                    report.recordLocation(location);
                    if (location.disposition() == ScreeningDisposition.REJECT) {
                        report.recordFinalReject();
                        continue;
                    }
                    EarlyCareerDecision career = earlyCareer.evaluate(raw);
                    report.recordCareer(career);
                    if (career.disposition() == ScreeningDisposition.REJECT) {
                        report.recordFinalReject();
                        continue;
                    }
                    report.recordLocationAndCareerEligible(raw);
                    RelevanceDecision role = relevance.evaluate(raw);
                    report.recordRelevance(role);
                    report.recordFinal(raw, ScreeningDecision.of(location, career, role));
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Live smoke source {} failed: {}", source.getSourceName(),
                        failure.getClass().getSimpleName());
            }
        }
        JobIngestionReport result = report.toReport();
        LOGGER.info("Live smoke result: fetched={}, uniqueRaw={}, bucharest={}, remoteRomania={}, "
                        + "remoteUnknown={}, rejectedRemote={}, rejectedOnsiteHybrid={}, "
                        + "earlyCareerEligible={}, earlyCareerUnknown={}, rejectedSeniority={}, "
                        + "locationCareerEligible={}, relevanceMatch={}, relevanceReview={}, "
                        + "rejectedByRelevance={}, finalMatch={}, finalReview={}, finalReject={}, "
                        + "duplicateRaw={}, existingUnchanged={}, persistedNew={}, updated={}, "
                        + "matchTenants={}, reviewTenants={}",
                result.totalVacanciesFetched(), result.totalUniqueVacanciesBeforeEligibilityFiltering(),
                result.bucharestLocalVacancies(), result.remoteVacanciesEligibleFromRomania(),
                result.remoteEligibilityUnknown(), result.rejectedByGeographicRestriction(),
                result.rejectedOnsiteOrHybridOutsideBucharest(), result.earlyCareerEligibleVacancies(),
                result.earlyCareerEligibilityUnknown(), result.rejectedBySeniorityOrExperience(),
                result.locationAndCareerEligibleVacancies(), result.relevanceMatchVacancies(),
                result.relevanceReviewVacancies(), result.rejectedByRelevance(),
                result.finalMatchVacancies(), result.finalReviewVacancies(),
                result.finalRejectVacancies(), result.duplicateRawVacancies(),
                result.existingUnchangedVacancies(), result.persistedNewVacancies(),
                result.updatedVacancies(),
                result.finalMatchTenantsByProvider(), result.finalReviewTenantsByProvider());
        return result;
    }

    private static final class MutableReport {
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
        private final Set<String> uniqueRaw = new LinkedHashSet<>();
        private final Set<String> locationCareer = new LinkedHashSet<>();
        private final Set<String> matches = new LinkedHashSet<>();
        private final Set<String> reviews = new LinkedHashSet<>();
        private final Map<String, Set<String>> locationCareerTenants = new LinkedHashMap<>();
        private final Map<String, Set<String>> matchTenants = new LinkedHashMap<>();
        private final Map<String, Set<String>> reviewTenants = new LinkedHashMap<>();

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
            locationCareer.add(key);
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
                matches.add(key);
                tenant(matchTenants, raw);
            } else if (screening.disposition() == ScreeningDisposition.REVIEW) {
                reviews.add(key);
                tenant(reviewTenants, raw);
            } else {
                recordFinalReject();
            }
        }

        private void recordFinalReject() {
            finalReject++;
        }

        private JobIngestionReport toReport() {
            return new JobIngestionReport(fetched, uniqueRaw.size(), bucharest, remoteRomania,
                    unknown, rejectedRemote, rejectedLocal, earlyEligible, earlyUnknown,
                    rejectedSeniority, locationCareer.size(), relevanceMatch, relevanceReview,
                    rejectedRelevance, matches.size(), reviews.size(), finalReject,
                    duplicateRaw, 0, 0, 0,
                    mapped(locationCareerTenants), mapped(matchTenants), mapped(reviewTenants));
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
