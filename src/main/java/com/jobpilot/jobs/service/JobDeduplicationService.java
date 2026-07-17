package com.jobpilot.jobs.service;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.repository.JobRepository;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class JobDeduplicationService {
    private final JobRepository repository;
    private final Clock clock;

    public JobDeduplicationService(JobRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<Job> findDuplicate(Job job) {
        Optional<Job> byUrl = repository.findByCanonicalUrl(job.getCanonicalUrl());
        if (byUrl.isPresent()) return byUrl;
        if (job.getExternalId() != null) {
            Optional<Job> byExternalId = repository.findBySourceAndExternalId(job.getSource(), job.getExternalId());
            if (byExternalId.isPresent()) return byExternalId;
        }
        return repository.findFirstByNormalizedFingerprintOrDescriptionHash(
                job.getNormalizedFingerprint(), job.getDescriptionHash());
    }

    public Job recordSeen(Job duplicate) {
        duplicate.seenAgain(clock.instant());
        return repository.save(duplicate);
    }
}
