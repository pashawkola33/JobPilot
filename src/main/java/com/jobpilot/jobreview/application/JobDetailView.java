package com.jobpilot.jobreview.application;

import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import java.time.Instant;
import java.util.List;

/**
 * Bounded vacancy projection for a single Telegram card. The provider description is
 * deliberately absent: raw provider text and provider HTML never reach a message.
 */
public record JobDetailView(
        long id,
        String title,
        String company,
        String location,
        String canonicalUrl,
        int score,
        ScreeningDisposition disposition,
        String eligibilityReason,
        String earlyCareerEligibilityReason,
        List<JobReasonView> reasons,
        String source,
        String tenant,
        Instant publishedAt,
        Instant firstSeenAt,
        WorkflowStatus workflowStatus,
        String note,
        Instant appliedAt
) {
    public JobDetailView {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
