package com.jobpilot.jobs.service;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class JobRelevanceFilter {
    private final List<String> searchTerms;

    public JobRelevanceFilter(JobPilotProperties properties) {
        this.searchTerms = properties.searchTerms().stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    public boolean isRelevant(RawJob job) {
        if (searchTerms.isEmpty()) return true;
        String text = (String.valueOf(job.title()) + " " + String.valueOf(job.description()))
                .toLowerCase(Locale.ROOT);
        return searchTerms.stream().anyMatch(text::contains);
    }
}
