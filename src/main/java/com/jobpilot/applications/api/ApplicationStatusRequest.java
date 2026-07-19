package com.jobpilot.applications.api;

import com.jobpilot.applications.domain.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record ApplicationStatusRequest(
        @NotNull ApplicationStatus status,
        Instant interviewAt,
        @Size(max = 1000) String rejectionReason) {
}
