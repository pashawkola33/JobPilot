package com.jobpilot.jobs.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobRequirement;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RelevanceDecision;
import com.jobpilot.jobs.domain.ScreeningDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.matching.JobScoreCalculator;
import com.jobpilot.matching.JobMatchingService;
import com.jobpilot.matching.ScoreCalculation;
import com.jobpilot.matching.ScoreCard;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobProcessor {
    private final JobNormalizer normalizer;
    private final LocationEligibilityService eligibility;
    private final EarlyCareerEligibilityService earlyCareerEligibility;
    private final JobRelevanceFilter relevance;
    private final JobDeduplicationService deduplication;
    private final JobScoreCalculator scoreCalculator;
    private final JobRepository jobs;
    private final JobRequirementRepository requirements;
    private final JobScoreRepository scores;
    private final ObjectMapper objectMapper;
    private final UrlCanonicalizer urlCanonicalizer;
    private final Clock clock;

    @Autowired
    public JobProcessor(JobNormalizer normalizer, LocationEligibilityService eligibility,
                        EarlyCareerEligibilityService earlyCareerEligibility,
                        JobRelevanceFilter relevance,
                        JobDeduplicationService deduplication,
                        JobScoreCalculator scoreCalculator,
                        JobRepository jobs, JobRequirementRepository requirements,
                        JobScoreRepository scores, ObjectMapper objectMapper,
                        UrlCanonicalizer urlCanonicalizer, Clock clock) {
        this.normalizer = normalizer;
        this.eligibility = eligibility;
        this.earlyCareerEligibility = earlyCareerEligibility;
        this.relevance = relevance;
        this.deduplication = deduplication;
        this.scoreCalculator = scoreCalculator;
        this.jobs = jobs;
        this.requirements = requirements;
        this.scores = scores;
        this.objectMapper = objectMapper;
        this.urlCanonicalizer = urlCanonicalizer;
        this.clock = clock;
    }

    /** Legacy construction shape retained for focused unit tests and non-Spring callers. */
    public JobProcessor(JobNormalizer normalizer, LocationEligibilityService eligibility,
                        EarlyCareerEligibilityService earlyCareerEligibility,
                        JobRelevanceFilter relevance, JobDeduplicationService deduplication,
                        DeterministicRequirementExtractor extractor, JobMatchingService matching,
                        JobRepository jobs, JobRequirementRepository requirements,
                        JobScoreRepository scores, ObjectMapper objectMapper,
                        UrlCanonicalizer urlCanonicalizer, Clock clock) {
        this(normalizer, eligibility, earlyCareerEligibility, relevance, deduplication,
                new JobScoreCalculator(extractor, matching), jobs, requirements, scores,
                objectMapper, urlCanonicalizer, clock);
    }

    @Transactional
    public JobProcessingResult process(RawJob raw) {
        LocationEligibilityDecision location = eligibility.evaluate(raw);
        if (location.disposition() == ScreeningDisposition.REJECT) {
            return reconcileRejected(raw, location, null, null);
        }
        EarlyCareerDecision career = earlyCareerEligibility.evaluate(raw);
        if (career.disposition() == ScreeningDisposition.REJECT) {
            return reconcileRejected(raw, location, career, null);
        }
        RelevanceDecision relevanceDecision = relevance.evaluate(raw);
        return process(raw, location, career, relevanceDecision);
    }

    @Transactional
    public JobProcessingResult process(RawJob raw, LocationEligibilityDecision decision) {
        if (decision.disposition() == ScreeningDisposition.REJECT) {
            return reconcileRejected(raw, decision, null, null);
        }
        return process(raw, decision, earlyCareerEligibility.evaluate(raw));
    }

    @Transactional
    public JobProcessingResult process(RawJob raw, LocationEligibilityDecision location,
                                       EarlyCareerDecision earlyCareer) {
        if (location.disposition() == ScreeningDisposition.REJECT) {
            return reconcileRejected(raw, location, null, null);
        }
        if (earlyCareer.disposition() == ScreeningDisposition.REJECT) {
            return reconcileRejected(raw, location, earlyCareer, null);
        }
        return process(raw, location, earlyCareer, relevance.evaluate(raw));
    }

    @Transactional
    public JobProcessingResult process(RawJob raw, LocationEligibilityDecision location,
                                       EarlyCareerDecision earlyCareer,
                                       RelevanceDecision relevanceDecision) {
        ScreeningDecision screening = ScreeningDecision.of(location, earlyCareer, relevanceDecision);
        if (screening.disposition() == ScreeningDisposition.REJECT) {
            return reconcileRejected(raw, location, earlyCareer, relevanceDecision);
        }
        Job normalized = normalizer.normalize(raw, location, earlyCareer, screening);
        Optional<Job> duplicate = deduplication.findDuplicate(normalized);
        if (duplicate.isEmpty()) {
            return new JobProcessingResult(normalized, extractScoreAndSave(normalized),
                    JobPersistenceOutcome.CREATED, location, earlyCareer, relevanceDecision, screening);
        }
        Job existing = duplicate.get();
        if (existing.getDescriptionHash().equals(normalized.getDescriptionHash())) {
            boolean meaningfulChange = existing.refreshScreening(normalized);
            deduplication.recordSeen(existing);
            if (meaningfulChange) {
                // Screening can change with title/location metadata, all of which feed extraction
                // or scoring, so rebuild both artifacts rather than retain a potentially stale score.
                return new JobProcessingResult(existing, extractScoreAndSave(existing),
                        JobPersistenceOutcome.UPDATED, location, earlyCareer,
                        relevanceDecision, screening);
            }
            ScoreCard existingScore = scores.findByJobId(existing.getId())
                    .map(JobScore::toValue).orElse(null);
            return new JobProcessingResult(existing, existingScore, JobPersistenceOutcome.UNCHANGED,
                    location, earlyCareer, relevanceDecision, screening);
        }
        existing.refreshContent(normalized, clock.instant());
        return new JobProcessingResult(existing, extractScoreAndSave(existing),
                JobPersistenceOutcome.UPDATED, location, earlyCareer, relevanceDecision, screening);
    }

    /**
     * Reconciles a hard rejection by stable identity without normalizing, extracting requirements,
     * calculating a score, or creating a job. Decisions omitted because of short-circuiting retain
     * their last persisted stage dispositions.
     */
    @Transactional
    public JobProcessingResult reconcileRejected(RawJob raw,
                                                  LocationEligibilityDecision location,
                                                  EarlyCareerDecision career,
                                                  RelevanceDecision relevanceDecision) {
        if (raw == null) throw new IllegalArgumentException("Raw job is required");
        ScreeningDecision current = ScreeningDecision.of(location, career, relevanceDecision);
        if (current.disposition() != ScreeningDisposition.REJECT) {
            throw new IllegalArgumentException("Reconciliation requires a hard rejection");
        }
        Optional<Job> stored = findExistingForReconciliation(raw);
        if (stored.isEmpty()) {
            return new JobProcessingResult(null, null, JobPersistenceOutcome.NOT_PERSISTED,
                    location, career, relevanceDecision, current);
        }

        Job existing = stored.get();
        boolean changed = existing.reconcileRejected(location, career, relevanceDecision,
                current.reasons(), clock.instant());
        Optional<JobScore> obsoleteScore = scores.findByJobId(existing.getId());
        if (obsoleteScore.isPresent()) {
            scores.delete(obsoleteScore.get());
            scores.flush();
            changed = true;
        }
        jobs.save(existing);
        ScreeningDecision persisted = new ScreeningDecision(ScreeningDisposition.REJECT,
                existing.getLocationDisposition(), existing.getCareerDisposition(),
                existing.getRelevanceDisposition(), current.reasons());
        return new JobProcessingResult(existing, null,
                changed ? JobPersistenceOutcome.UPDATED : JobPersistenceOutcome.UNCHANGED,
                location, career, relevanceDecision, persisted);
    }

    private Optional<Job> findExistingForReconciliation(RawJob raw) {
        String source = blankToNull(raw.source());
        String tenant = blankToNull(raw.providerTenant());
        String externalId = blankToNull(raw.externalId());
        if (source != null && tenant != null && externalId != null) {
            return jobs.findByStableIdentityForUpdate(source, tenant, externalId);
        }
        String url = blankToNull(raw.url());
        if (url == null) return Optional.empty();
        try {
            return jobs.findByCanonicalUrlForUpdate(urlCanonicalizer.canonicalize(url).toString());
        } catch (IllegalArgumentException invalidUrl) {
            return Optional.empty();
        }
    }

    private ScoreCard extractScoreAndSave(Job job) {
        ScoreCalculation calculation = scoreCalculator.calculate(job);
        ExtractedRequirements extracted = calculation.requirements();
        job.applyRequirements(extracted, join(extracted.technologies()), join(extracted.spokenLanguages()));
        ScoreCard card = calculation.score();
        Job saved = jobs.save(job);
        requirements.findByJobId(saved.getId()).ifPresent(outdated -> {
            requirements.delete(outdated);
            requirements.flush();
        });
        requirements.save(new JobRequirement(saved, extracted, join(extracted.technologies()),
                join(extracted.programmingLanguages()), join(extracted.spokenLanguages()),
                join(extracted.mentorshipSignals()), json(extracted)));
        scores.findByJobId(saved.getId()).ifPresent(outdated -> {
            scores.delete(outdated);
            scores.flush();
        });
        scores.save(new JobScore(saved, card, clock.instant()));
        return card;
    }

    private String join(java.util.List<String> values) {
        return String.join("|", values);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize deterministic screening data", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
