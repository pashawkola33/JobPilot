package com.jobpilot.sources;

import com.jobpilot.jobs.domain.RawJob;
import java.util.List;

public interface JobSource {
    String getSourceName();
    List<RawJob> fetchJobs();
}
