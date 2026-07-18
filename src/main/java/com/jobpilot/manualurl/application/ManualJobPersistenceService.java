package com.jobpilot.manualurl.application;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.jobs.service.JobNormalizer;
import com.jobpilot.jobs.service.JobProcessingResult;
import com.jobpilot.jobs.service.JobProcessor;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class ManualJobPersistenceService {
    private final JobProcessor processor;
    private final JobNormalizer normalizer;
    private final JobRepository jobs;
    private final JobScoreRepository scores;

    public ManualJobPersistenceService(JobProcessor processor, JobNormalizer normalizer,
                                       JobRepository jobs, JobScoreRepository scores) {
        this.processor = processor;
        this.normalizer = normalizer;
        this.jobs = jobs;
        this.scores = scores;
    }

    public JobProcessingResult persist(RawJob rawJob) {
        try {
            return processor.process(rawJob);
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            Optional<Job> existing = findExisting(rawJob);
            if (existing.isEmpty()) {
                throw concurrentDuplicate;
            }
            Job job = existing.get();
            return new JobProcessingResult(job,
                    scores.findByJobId(job.getId()).map(score -> score.toValue()).orElse(null), false);
        }
    }

    private Optional<Job> findExisting(RawJob rawJob) {
        if (rawJob.externalId() != null && !rawJob.externalId().isBlank()) {
            Optional<Job> byExternalId = jobs.findBySourceAndExternalId(
                    rawJob.source(), rawJob.externalId());
            if (byExternalId.isPresent()) {
                return byExternalId;
            }
        }
        return jobs.findByCanonicalUrl(normalizer.canonicalizeUrl(rawJob.url()));
    }
}
