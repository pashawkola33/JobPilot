package com.jobpilot.candidate.service;

import static com.jobpilot.candidate.CandidateProfileTestData.skill;
import static com.jobpilot.candidate.CandidateProfileTestData.validProfile;
import static com.jobpilot.candidate.CandidateProfileTestData.withCandidateKey;
import static com.jobpilot.candidate.CandidateProfileTestData.withSkills;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.candidate.domain.Candidate;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import com.jobpilot.candidate.repository.CandidateRepository;
import java.math.BigDecimal;
import jakarta.validation.Validation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class CandidateProfileBootstrapServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");

    @Test
    void invalidProfileFailsBeforePersistence() {
        CandidateProfileRepository repository = mock(CandidateProfileRepository.class);
        CandidateRepository candidates = mock(CandidateRepository.class);
        var service = service(repository, candidates);
        var invalid = withSkills(validProfile(2), List.of(
                skill("java", "java", 0), skill("java", "spring", 1)));

        assertThatThrownBy(() -> service.bootstrap(invalid))
                .isInstanceOf(CandidateProfileValidationException.class)
                .hasMessageContaining("duplicate stable skill key");
        verifyNoInteractions(repository);
        verifyNoInteractions(candidates);
    }

    @Test
    void changedFactsCannotOverwriteAnExistingProfileVersion() {
        CandidateProfileRepository repository = mock(CandidateProfileRepository.class);
        CandidateRepository candidates = mock(CandidateRepository.class);
        Candidate candidate = candidate("default", 7L);
        CandidateProfile stored = new CandidateProfile(candidate, 1, "Stored Candidate",
                "Bucharest, Romania", "Stored University", "BSc", 2025, null, true, false,
                BigDecimal.ZERO, "stored-source-hash", NOW, true);
        when(candidates.findByStableKey("default")).thenReturn(Optional.of(candidate));
        when(repository.findByCandidateIdAndActiveTrue(7L)).thenReturn(Optional.of(stored));
        var service = service(repository, candidates);

        assertThatThrownBy(() -> service.bootstrap(validProfile(1)))
                .isInstanceOf(CandidateProfileVersionConflictException.class)
                .hasMessageContaining("facts changed without increasing profileVersion");
        verify(repository, never()).saveAndFlush(any());
    }

    /**
     * The source hash fingerprints candidate facts. Ownership is not one of them, so re-keying
     * the owner must not make an already stored profile version look changed -- which is exactly
     * what would strand an existing database on "facts changed without increasing profileVersion".
     */
    @Test
    void owningCandidateIsNotPartOfTheProfileFingerprint() {
        assertThat(bootstrappedSourceHash(withCandidateKey(validProfile(1), "second-candidate")))
                .isEqualTo(bootstrappedSourceHash(validProfile(1)));
    }

    private String bootstrappedSourceHash(CandidateProfileProperties definition) {
        CandidateProfileRepository repository = mock(CandidateProfileRepository.class);
        CandidateRepository candidates = mock(CandidateRepository.class);
        when(candidates.findByStableKey(definition.candidateKey()))
                .thenReturn(Optional.of(candidate(definition.candidateKey(), 7L)));
        when(repository.findByCandidateIdAndActiveTrue(7L)).thenReturn(Optional.empty());
        when(repository.findByCandidateIdAndProfileVersion(7L, definition.profileVersion()))
                .thenReturn(Optional.empty());

        service(repository, candidates).bootstrap(definition);

        ArgumentCaptor<CandidateProfile> saved = ArgumentCaptor.forClass(CandidateProfile.class);
        verify(repository).saveAndFlush(saved.capture());
        return saved.getValue().getSourceHash();
    }

    private CandidateProfileBootstrapService service(CandidateProfileRepository repository,
                                                     CandidateRepository candidates) {
        var validator = new CandidateProfileDefinitionValidator(
                Validation.buildDefaultValidatorFactory().getValidator());
        return new CandidateProfileBootstrapService(repository, candidates, validator,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Candidate candidate(String stableKey, long id) {
        Candidate candidate = new Candidate(stableKey, NOW);
        ReflectionTestUtils.setField(candidate, "id", id);
        return candidate;
    }
}
