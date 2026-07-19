package com.jobpilot.applications.application;

import com.jobpilot.applications.domain.ApplicationStatus;
import java.time.Instant;
import java.time.LocalDate;

public record ApplicationView(
        Long applicationId,
        Long jobId,
        String title,
        String company,
        String canonicalUrl,
        ApplicationStatus status,
        Instant applicationDate,
        Instant interviewDate,
        LocalDate nextFollowUpDate,
        String notes,
        String rejectionReason,
        Instant updatedAt) {
}
