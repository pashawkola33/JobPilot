package com.jobpilot.candidate.service;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.candidate.repository.CandidateRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Resolves the configured compatibility identity for candidate-specific runtime workflows. */
@Service
public class RuntimeCandidateContext {
    private final CandidateProfileProperties properties;
    private final CandidateRepository candidates;

    public RuntimeCandidateContext(CandidateProfileProperties properties,
                                   CandidateRepository candidates) {
        this.properties = properties;
        this.candidates = candidates;
    }

    public Optional<Long> candidateId() {
        return candidates.findByStableKey(properties.candidateKey())
                .map(candidate -> candidate.getId());
    }
}
