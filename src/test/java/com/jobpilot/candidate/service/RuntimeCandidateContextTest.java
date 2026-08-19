package com.jobpilot.candidate.service;

import static com.jobpilot.candidate.CandidateProfileTestData.validProfile;
import static com.jobpilot.candidate.CandidateProfileTestData.withCandidateKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jobpilot.candidate.domain.Candidate;
import com.jobpilot.candidate.repository.CandidateRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeCandidateContextTest {
    private final CandidateRepository candidates = mock(CandidateRepository.class);

    @Test
    void resolvesOnlyTheConfiguredStableKeyToPersistentIdentity() {
        Candidate candidate = mock(Candidate.class);
        RuntimeCandidateContext context = new RuntimeCandidateContext(
                withCandidateKey(validProfile(1), "configured-candidate"), candidates);
        when(candidates.findByStableKey("configured-candidate")).thenReturn(Optional.of(candidate));
        when(candidate.getId()).thenReturn(42L);

        assertThat(context.candidateId()).contains(42L);

        verify(candidates).findByStableKey("configured-candidate");
        verifyNoMoreInteractions(candidates);
    }

    @Test
    void missingConfiguredCandidateReturnsAbsentWithoutCreatingOrFallingBack() {
        RuntimeCandidateContext context = new RuntimeCandidateContext(
                withCandidateKey(validProfile(1), "missing-candidate"), candidates);
        when(candidates.findByStableKey("missing-candidate")).thenReturn(Optional.empty());

        assertThat(context.candidateId()).isEmpty();

        verify(candidates).findByStableKey("missing-candidate");
        verifyNoMoreInteractions(candidates);
    }
}
