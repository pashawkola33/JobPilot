package com.jobpilot.candidate.service;

import static com.jobpilot.candidate.CandidateProfileTestData.skill;
import static com.jobpilot.candidate.CandidateProfileTestData.validProfile;
import static com.jobpilot.candidate.CandidateProfileTestData.withSkills;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.repository.CandidateProfileRepository;
import java.math.BigDecimal;
import jakarta.validation.Validation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CandidateProfileBootstrapServiceTest {
    @Test
    void invalidProfileFailsBeforePersistence() {
        CandidateProfileRepository repository = mock(CandidateProfileRepository.class);
        var validator = new CandidateProfileDefinitionValidator(
                Validation.buildDefaultValidatorFactory().getValidator());
        var service = new CandidateProfileBootstrapService(repository, validator,
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));
        var invalid = withSkills(validProfile(2), List.of(
                skill("java", "java", 0), skill("java", "spring", 1)));

        assertThatThrownBy(() -> service.bootstrap(invalid))
                .isInstanceOf(CandidateProfileValidationException.class)
                .hasMessageContaining("duplicate stable skill key");
        verifyNoInteractions(repository);
    }

    @Test
    void changedFactsCannotOverwriteAnExistingProfileVersion() {
        CandidateProfileRepository repository = mock(CandidateProfileRepository.class);
        CandidateProfile stored = new CandidateProfile(1, "Stored Candidate", "Bucharest, Romania",
                "Stored University", "BSc", 2025, null, true, false, BigDecimal.ZERO,
                "stored-source-hash", Instant.parse("2026-07-19T00:00:00Z"), true);
        when(repository.findByActiveTrue()).thenReturn(Optional.of(stored));
        var validator = new CandidateProfileDefinitionValidator(
                Validation.buildDefaultValidatorFactory().getValidator());
        var service = new CandidateProfileBootstrapService(repository, validator,
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-07-19T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.bootstrap(validProfile(1)))
                .isInstanceOf(CandidateProfileVersionConflictException.class)
                .hasMessageContaining("facts changed without increasing profileVersion");
        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }
}
