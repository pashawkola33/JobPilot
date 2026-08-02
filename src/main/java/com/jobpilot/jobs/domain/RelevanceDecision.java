package com.jobpilot.jobs.domain;

import java.util.List;

public record RelevanceDecision(
        ScreeningDisposition disposition,
        List<ScreeningReason> reasons) {

    public RelevanceDecision {
        disposition = disposition == null ? ScreeningDisposition.REVIEW : disposition;
        reasons = reasons == null || reasons.isEmpty()
                ? List.of(new ScreeningReason(ScreeningStage.ROLE_RELEVANCE,
                "RELEVANCE_UNCERTAIN", "Role relevance could not be determined"))
                : List.copyOf(reasons);
    }
}
