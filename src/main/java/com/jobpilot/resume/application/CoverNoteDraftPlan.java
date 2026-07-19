package com.jobpilot.resume.application;

import java.util.List;

/** Stable candidate fact and vacancy evidence keys only; no provider prose becomes canonical. */
public record CoverNoteDraftPlan(List<String> candidateFactKeys,
                                 List<String> vacancyEvidenceKeys) {
    public CoverNoteDraftPlan {
        candidateFactKeys = List.copyOf(candidateFactKeys);
        vacancyEvidenceKeys = List.copyOf(vacancyEvidenceKeys);
    }
}
