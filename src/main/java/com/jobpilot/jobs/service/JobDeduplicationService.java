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
            Optional<Job> byExternalId = repository.findBySourceAndProviderTenantAndExternalId(
                    job.getSource(), job.getProviderTenant(), job.getExternalId());
            if (byExternalId.isPresent()) return byExternalId;
        }
        Optional<Job> byFingerprint = repository.findFirstByNormalizedFingerprint(job.getNormalizedFingerprint());
        if (byFingerprint.isPresent() && (job.getExternalId() == null
                || !byFingerprint.get().getSource().equals(job.getSource()))) {
            return byFingerprint;
        }
        if (job.getDescription().isBlank()) return Optional.empty();
        // Identical descriptions only count as duplicates within the same company;
        // unrelated companies often share boilerplate vacancy text.
        Optional<Job> byDescription = repository.findFirstByCompanyAndDescriptionHash(
                job.getCompany(), job.getDescriptionHash());
        if (job.getExternalId() != null && byDescription.isPresent()
                && byDescription.get().getSource().equals(job.getSource())) return Optional.empty();
        return byDescription;
    }

    public Job recordSeen(Job duplicate) {
        duplicate.seenAgain(clock.instant());
        return repository.save(duplicate);
    }
}
