package com.jobpilot.jobs.service;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.matching.ScoreCard;

public record JobProcessingResult(
        Job job,
        ScoreCard score,
        boolean newlyCreated,
        LocationEligibilityDecision eligibilityDecision,
        EarlyCareerDecision earlyCareerDecision) {

    public JobProcessingResult(Job job, ScoreCard score, boolean newlyCreated) {
        this(job, score, newlyCreated, null, null);
    }

    public JobProcessingResult(Job job, ScoreCard score, boolean newlyCreated,
                               LocationEligibilityDecision eligibilityDecision) {
        this(job, score, newlyCreated, eligibilityDecision, null);
    }

    public static JobProcessingResult locationIneligible(LocationEligibilityDecision decision) {
        return new JobProcessingResult(null, null, false, decision, null);
    }

    public static JobProcessingResult earlyCareerIneligible(LocationEligibilityDecision location,
                                                             EarlyCareerDecision earlyCareer) {
        return new JobProcessingResult(null, null, false, location, earlyCareer);
    }

    public boolean accepted() {
        if (eligibilityDecision != null && !eligibilityDecision.accepted()) return false;
        if (earlyCareerDecision != null && !earlyCareerDecision.accepted()) return false;
        return job != null;
    }
}
