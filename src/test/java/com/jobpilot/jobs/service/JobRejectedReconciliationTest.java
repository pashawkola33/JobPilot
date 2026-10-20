package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.extraction.DeterministicRequirementExtractor;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.jobs.repository.JobRequirementRepository;
import com.jobpilot.jobs.repository.JobScoreRepository;
import com.jobpilot.matching.JobMatchingService;
import com.jobpilot.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JobRejectedReconciliationTest {
    @Test
    void newRejectedIdentityPersistsCatalogueWithoutLegacyExtractionOrScoring() {
        JobNormalizer normalizer = mock(JobNormalizer.class);
        LocationEligibilityService locationService = mock(LocationEligibilityService.class);
        EarlyCareerEligibilityService career = mock(EarlyCareerEligibilityService.class);
        JobRelevanceFilter relevance = mock(JobRelevanceFilter.class);
        JobDeduplicationService deduplication = mock(JobDeduplicationService.class);
        DeterministicRequirementExtractor extractor = mock(DeterministicRequirementExtractor.class);
        JobMatchingService matching = mock(JobMatchingService.class);
        JobRepository jobs = mock(JobRepository.class);
        JobRequirementRepository requirements = mock(JobRequirementRepository.class);
        JobScoreRepository scores = mock(JobScoreRepository.class);
        UrlCanonicalizer canonicalizer = mock(UrlCanonicalizer.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);
        JobProcessor processor = new JobProcessor(normalizer, locationService, career, relevance,
                deduplication, extractor, matching, jobs, requirements, scores,
                new ObjectMapper(), canonicalizer, clock);
        RawJob raw = new RawJob("greenhouse", "new-reject",
                "https://example.com/jobs/new-reject", "Java Developer", "Example",
                "USA | Remote", "Build Java services.", null, null, null, "payload");
        var location = new LocationEligibilityService(TestProperties.create()).evaluate(raw);
        when(jobs.findByStableIdentityForUpdate(
                raw.source(), raw.providerTenant(), raw.externalId())).thenReturn(Optional.empty());

        var normalized=new JobNormalizer(clock,new UrlCanonicalizer()).normalize(raw,location,null,com.jobpilot.jobs.domain.ScreeningDecision.of(location,null,null));
        when(normalizer.normalize(org.mockito.ArgumentMatchers.eq(raw),org.mockito.ArgumentMatchers.eq(location),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.any())).thenReturn(normalized);
        when(deduplication.findDuplicate(normalized)).thenReturn(Optional.empty());
        JobProcessingResult result = processor.reconcileRejected(raw, location, null, null);

        assertThat(result.persistenceOutcome()).isEqualTo(JobPersistenceOutcome.CREATED);
        assertThat(result.job()).isSameAs(normalized);
        assertThat(normalized.isLegacyAdmissionPending()).isTrue();
        assertThat(result.score()).isNull();
        verify(jobs).findByStableIdentityForUpdate(
                raw.source(), raw.providerTenant(), raw.externalId());
        verify(jobs).save(normalized);
        verifyNoInteractions(locationService, career, relevance,
                extractor, matching, requirements, scores, canonicalizer);
    }
}
