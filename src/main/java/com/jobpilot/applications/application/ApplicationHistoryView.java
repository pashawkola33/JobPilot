package com.jobpilot.applications.application;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.domain.ApplicationStatusChangeSource;
import java.time.Instant;

public record ApplicationHistoryView(
        long id,
        ApplicationStatus previousStatus,
        ApplicationStatus newStatus,
        Instant changedAt,
        ApplicationStatusChangeSource source) {
}
