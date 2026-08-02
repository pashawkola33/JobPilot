package com.jobpilot.jobs.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobRequirement;
import com.jobpilot.jobs.domain.JobScore;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.matching.JobMatchingService;
import com.jobpilot.matching.ScoreCard;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobProcessor {
    private final JobNormalizer normalizer;
    private final LocationEligibilityService eligibility;
    private final EarlyCareerEligibilityService earlyCareerEligibility;
    private final JobDeduplicationService deduplication;
    private final DeterministicRequirementExtractor extractor;
    private final JobMatchingService matching;
    private final JobRepository jobs;
    private final JobRequirementRepository requirements;
    private final JobScoreRepository scores;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JobProcessor(JobNormalizer normalizer, LocationEligibilityService eligibility,
                        EarlyCareerEligibilityService earlyCareerEligibility,
                        JobDeduplicationService deduplication,
                        DeterministicRequirementExtractor extractor, JobMatchingService matching,
                        JobRepository jobs, JobRequirementRepository requirements,
                        JobScoreRepository scores, ObjectMapper objectMapper, Clock clock) {
        this.normalizer = normalizer;
        this.eligibility = eligibility;
        this.earlyCareerEligibility = earlyCareerEligibility;
        this.deduplication = deduplication;
        this.extractor = extractor;
        this.matching = matching;
        this.jobs = jobs;
        this.requirements = requirements;
        this.scores = scores;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public JobProcessingResult process(RawJob raw) {
        return process(raw, eligibility.evaluate(raw));
    }

    @Transactional
    public JobProcessingResult process(RawJob raw, LocationEligibilityDecision decision) {
        if (!decision.accepted()) return JobProcessingResult.locationIneligible(decision);
        return process(raw, decision, earlyCareerEligibility.evaluate(raw));
    }

    @Transactional
    public JobProcessingResult process(RawJob raw, LocationEligibilityDecision location,
                                       EarlyCareerDecision earlyCareer) {
        if (!location.accepted()) return JobProcessingResult.locationIneligible(location);
        if (!earlyCareer.accepted()) {
            return JobProcessingResult.earlyCareerIneligible(location, earlyCareer);
        }
        Job normalized = normalizer.normalize(raw, location, earlyCareer);
        Optional<Job> duplicate = deduplication.findDuplicate(normalized);
        if (duplicate.isEmpty()) {
            return new JobProcessingResult(normalized, extractScoreAndSave(normalized), true,
                    location, earlyCareer);
        }
        Job existing = duplicate.get();
        if (existing.getDescriptionHash().equals(normalized.getDescriptionHash())) {
            deduplication.recordSeen(existing);
            ScoreCard existingScore = scores.findByJobId(existing.getId())
                    .map(JobScore::toValue).orElse(null);
            return new JobProcessingResult(existing, existingScore, false, location, earlyCareer);
        }
        existing.refreshContent(normalized, clock.instant());
        return new JobProcessingResult(existing, extractScoreAndSave(existing), false,
                location, earlyCareer);
    }

    private ScoreCard extractScoreAndSave(Job job) {
        ExtractedRequirements extracted = extractor.extract(job);
        job.applyRequirements(extracted, join(extracted.technologies()), join(extracted.spokenLanguages()));
        ScoreCard card = matching.score(job, extracted);
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

    private String json(ExtractedRequirements requirements) {
        try {
            return objectMapper.writeValueAsString(requirements);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize deterministic extraction", exception);
        }
    }
}
