package com.jobpilot.miniapp.api;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.jobreview.domain.WorkflowStatus;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import java.time.Instant;
import java.util.List;

/**
 * Everything one Mini App session reads, in one bounded response.
 *
 * <p>Deliberately absent: provider tenant, external id, screening codes, note text, and the
 * database's own identifiers beyond the job id the client must send back. Fields JobPilot
 * genuinely does not have without an LLM call — a match summary, extracted requirements, a
 * combined activity feed — are omitted rather than invented.
 */
public record MiniAppSnapshot(List<MiniAppJob> jobs, List<MiniAppApplication> applications) {

    public record MiniAppJob(
            long id,
            String title,
            String company,
            String location,
            String remoteType,
            String seniority,
            String employmentType,
            int score,
            String band,
            ScreeningDisposition disposition,
            WorkflowStatus workflowStatus,
            String source,
            Instant publishedAt,
            String canonicalUrl,
            List<String> strengths,
            List<String> risks) {
    }

    public record MiniAppApplication(
            long jobId,
            String title,
            String company,
            ApplicationStatus status,
            String canonicalUrl,
            Instant updatedAt,
            Instant appliedAt,
            String nextFollowUpDate) {
    }
}
