package com.jobpilot.observability;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.llm.domain.JobAnalysisStatus;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.resume.domain.DocumentRenderStatus;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/operations")
public class OperationalMetricsController {
    private final OperationalCounters counters;
    private final ApplicationRepository applications;
    private final JobAnalysisRepository analyses;
    private final ResumeVersionRepository resumes;
    private final CoverNoteRepository coverNotes;

    public OperationalMetricsController(OperationalCounters counters,
                                        ApplicationRepository applications,
                                        JobAnalysisRepository analyses,
                                        ResumeVersionRepository resumes,
                                        CoverNoteRepository coverNotes) {
        this.counters = counters;
        this.applications = applications;
        this.analyses = analyses;
        this.resumes = resumes;
        this.coverNotes = coverNotes;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Long> applicationStatuses = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            applicationStatuses.put(status.name(), applications.countByStatus(status));
        }
        Map<String, Long> analysisStatuses = new LinkedHashMap<>();
        for (JobAnalysisStatus status : JobAnalysisStatus.values()) {
            analysisStatuses.put(status.name(), analyses.countByStatus(status));
        }
        Map<String, Long> documentStatuses = new LinkedHashMap<>();
        for (DocumentRenderStatus status : DocumentRenderStatus.values()) {
            documentStatuses.put(status.name(),
                    resumes.countByRenderStatus(status) + coverNotes.countByRenderStatus(status));
        }
        return Map.of("runtimeCounters", counters.snapshot(),
                "applicationsByStatus", applicationStatuses,
                "analysesByStatus", analysisStatuses,
                "documentsByStatus", documentStatuses);
    }
}
