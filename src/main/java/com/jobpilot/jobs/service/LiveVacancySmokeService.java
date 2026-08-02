package com.jobpilot.jobs.service;

import com.jobpilot.common.Utf16;

import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.EarlyCareerEligibility;
import com.jobpilot.jobs.domain.RawJob;
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
    private final UrlCanonicalizer canonicalizer;

    public LiveVacancySmokeService(List<JobSource> sources, LocationEligibilityService eligibility,
                                   EarlyCareerEligibilityService earlyCareer,
                                   UrlCanonicalizer canonicalizer) {
        this.sources = List.copyOf(sources);
        this.eligibility = eligibility;
        this.earlyCareer = earlyCareer;
        this.canonicalizer = canonicalizer;
    }

    public JobIngestionReport collect() {
        MutableReport report = new MutableReport();
        for (JobSource source : sources) {
            try {
                for (RawJob raw : source.fetchJobs()) {
                    LocationEligibilityDecision locationDecision = eligibility.evaluate(raw);
                    EarlyCareerDecision careerDecision = locationDecision.accepted()
                            ? earlyCareer.evaluate(raw) : null;
                    report.record(raw, locationDecision, careerDecision, canonicalizer);
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("Live smoke source {} failed: {}", source.getSourceName(),
                        failure.getClass().getSimpleName());
            }
        }
        JobIngestionReport result = report.toReport();
        LOGGER.info("Live smoke result: fetched={}, uniqueRaw={}, bucharest={}, remoteRomania={}, "
                        + "remoteUnknown={}, rejectedRemote={}, rejectedOnsiteHybrid={}, finalEligible={}, "
                        + "earlyCareerEligible={}, earlyCareerUnknown={}, rejectedSeniority={}, "
                        + "rawTargetMet={}, locationTargetMet={}, estimatedAdditionalBoards={}, eligibleTenants={}",
                result.totalVacanciesFetched(), result.totalUniqueVacanciesBeforeEligibilityFiltering(),
                result.bucharestLocalVacancies(), result.remoteVacanciesEligibleFromRomania(),
                result.remoteEligibilityUnknown(), result.rejectedByGeographicRestriction(),
                result.rejectedOnsiteOrHybridOutsideBucharest(), result.finalUniqueEligibleVacancies(),
                result.earlyCareerEligibleVacancies(), result.earlyCareerEligibilityUnknown(),
                result.rejectedBySeniorityOrExperience(),
                result.rawTargetMet(), result.locationEligibleTargetMet(),
                result.estimatedAdditionalRelevantTenantBoardsNeeded(), result.eligibleTenantsByProvider());
        LOGGER.debug("Early-career UNKNOWN live samples: {}", report.unknownSamples());
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
        private final Set<String> uniqueRaw = new LinkedHashSet<>();
        private final Set<String> uniqueEligible = new LinkedHashSet<>();
        private final Map<String, Set<String>> tenants = new LinkedHashMap<>();
        private final java.util.ArrayList<String> unknownSamples = new java.util.ArrayList<>();

        private void record(RawJob raw, LocationEligibilityDecision decision,
                            EarlyCareerDecision careerDecision,
                            UrlCanonicalizer canonicalizer) {
            fetched++;
            String key = key(raw, canonicalizer);
            if (!uniqueRaw.add(key)) return;
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
                    if (unknownSamples.size() < 100) {
                        unknownSamples.add(safe(raw.source()) + "/" + safe(raw.providerTenant())
                                + ": " + bounded(raw.title()));
                    }
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
            Map<String, List<String>> mapped = new LinkedHashMap<>();
            tenants.forEach((provider, values) -> mapped.put(provider, List.copyOf(values)));
            return new JobIngestionReport(fetched, uniqueRaw.size(), bucharest, remoteRomania,
                    unknown, rejectedRemote, rejectedLocal, earlyEligible, earlyUnknown,
                    rejectedSeniority, uniqueEligible.size(), mapped);
        }

        private String key(RawJob raw, UrlCanonicalizer canonicalizer) {
            try {
                return "url:" + canonicalizer.canonicalize(raw.url());
            } catch (RuntimeException invalidUrl) {
            if (raw.externalId() != null && !raw.externalId().isBlank()) {
                    return "external:" + safe(raw.source()) + ":" + safe(raw.providerTenant())
                            + ":" + raw.externalId().strip();
                }
                return "content:" + safe(raw.company()) + "|" + safe(raw.title())
                        + "|" + safe(raw.location());
            }
        }

        private String safe(String value) {
            return value == null ? "" : value.strip();
        }

        private List<String> unknownSamples() {
            return List.copyOf(unknownSamples);
        }

        private String bounded(String value) {
            String safeValue = safe(value);
            return Utf16.truncate(safeValue, 160);
        }
    }
}
