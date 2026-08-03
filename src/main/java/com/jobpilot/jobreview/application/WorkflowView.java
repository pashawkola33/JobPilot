package com.jobpilot.jobreview.application;

import com.jobpilot.jobreview.domain.WorkflowStatus;
import java.time.Instant;

public record WorkflowView(
        long jobId,
        WorkflowStatus status,
        String note,
        Instant appliedAt,
        Instant updatedAt,
        boolean changed
) {
}
