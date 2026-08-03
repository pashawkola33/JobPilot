package com.jobpilot.jobreview.application;

import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import java.time.Instant;

public record JobQueueItem(
        long id,
        String title,
        String company,
        String location,
        ScreeningDisposition disposition,
        int score,
        WorkflowStatus workflowStatus,
        String source,
        String tenant,
        Instant publishedAt,
        String canonicalUrl
) {
}
