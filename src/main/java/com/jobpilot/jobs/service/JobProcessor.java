package com.jobpilot.jobs.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.JobRequirement;
import com.jobpilot.jobs.domain.JobScore;
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
    private final JobDeduplicationService deduplication;
    private final DeterministicRequirementExtractor extractor;
    private final JobMatchingService matching;
    private final JobRepository jobs;
    private final JobRequirementRepository requirements;
    private final JobScoreRepository scores;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JobProcessor(JobNormalizer normalizer, JobDeduplicationService deduplication,
                        DeterministicRequirementExtractor extractor, JobMatchingService matching,
                        JobRepository jobs, JobRequirementRepository requirements,
                        JobScoreRepository scores, ObjectMapper objectMapper, Clock clock) {
        this.normalizer = normalizer;
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
        Job normalized = normalizer.normalize(raw);
        Optional<Job> duplicate = deduplication.findDuplicate(normalized);
        if (duplicate.isPresent()) {
            Job existing = deduplication.recordSeen(duplicate.get());
            ScoreCard existingScore = scores.findByJobId(existing.getId())
                    .map(JobScore::toValue).orElse(null);
            return new JobProcessingResult(existing, existingScore, false);
        }
        ExtractedRequirements extracted = extractor.extract(normalized);
        normalized.applyRequirements(extracted, join(extracted.technologies()), join(extracted.spokenLanguages()));
        Job saved = jobs.save(normalized);
        requirements.save(new JobRequirement(saved, extracted, join(extracted.technologies()),
                join(extracted.programmingLanguages()), join(extracted.spokenLanguages()),
                join(extracted.mentorshipSignals()), json(extracted)));
        ScoreCard card = matching.score(saved, extracted);
        scores.save(new JobScore(saved, card, clock.instant()));
        return new JobProcessingResult(saved, card, true);
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
