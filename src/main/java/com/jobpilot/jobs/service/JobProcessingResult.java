package com.jobpilot.jobs.service;

import com.jobpilot.jobs.domain.EarlyCareerDecision;
import com.jobpilot.jobs.domain.Job;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RelevanceDecision;
import com.jobpilot.jobs.domain.ScreeningDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.matching.ScoreCard;

public record JobProcessingResult(
        Job job,
        ScoreCard score,
        JobPersistenceOutcome persistenceOutcome,
        LocationEligibilityDecision eligibilityDecision,
        EarlyCareerDecision earlyCareerDecision,
        RelevanceDecision relevanceDecision,
        ScreeningDecision screeningDecision) {

    public JobProcessingResult {
        persistenceOutcome = persistenceOutcome == null
                ? JobPersistenceOutcome.NOT_PERSISTED : persistenceOutcome;
    }

    public JobProcessingResult(Job job, ScoreCard score, boolean newlyCreated) {
        this(job, score, outcome(job, newlyCreated), null, null, null, null);
    }

    public JobProcessingResult(Job job, ScoreCard score, boolean newlyCreated,
                               LocationEligibilityDecision eligibilityDecision) {
        this(job, score, outcome(job, newlyCreated), eligibilityDecision, null, null, null);
    }

    public JobProcessingResult(Job job, ScoreCard score, boolean newlyCreated,
                               LocationEligibilityDecision eligibilityDecision,
                               EarlyCareerDecision earlyCareerDecision) {
        this(job, score, outcome(job, newlyCreated), eligibilityDecision,
                earlyCareerDecision, null, null);
    }

    public static JobProcessingResult rejected(LocationEligibilityDecision location,
                                               EarlyCareerDecision career,
                                               RelevanceDecision relevance,
                                               ScreeningDecision screening) {
        return new JobProcessingResult(null, null, JobPersistenceOutcome.NOT_PERSISTED,
                location, career, relevance, screening);
    }

    public static JobProcessingResult locationIneligible(LocationEligibilityDecision decision) {
        return rejected(decision, null, null, null);
    }

    public static JobProcessingResult earlyCareerIneligible(LocationEligibilityDecision location,
                                                             EarlyCareerDecision earlyCareer) {
        return rejected(location, earlyCareer, null, null);
    }

    public boolean newlyCreated() {
        return persistenceOutcome == JobPersistenceOutcome.CREATED;
    }

    public boolean updated() {
        return persistenceOutcome == JobPersistenceOutcome.UPDATED;
    }

    public boolean unchanged() {
        return persistenceOutcome == JobPersistenceOutcome.UNCHANGED;
    }

    /** Compatibility alias: an unchanged persisted vacancy is an existing duplicate. */
    public boolean duplicate() {
        return unchanged();
    }

    public boolean persisted() {
        return persistenceOutcome != JobPersistenceOutcome.NOT_PERSISTED && job != null;
    }

    public boolean accepted() {
        return persisted() && (screeningDecision == null
                || screeningDecision.disposition() != ScreeningDisposition.REJECT);
    }

    public ScreeningDisposition finalDisposition() {
        if (screeningDecision != null) return screeningDecision.disposition();
        return job == null ? ScreeningDisposition.REJECT : job.getScreeningDisposition();
    }

    private static JobPersistenceOutcome outcome(Job job, boolean newlyCreated) {
        if (job == null) return JobPersistenceOutcome.NOT_PERSISTED;
        return newlyCreated ? JobPersistenceOutcome.CREATED : JobPersistenceOutcome.UNCHANGED;
    }
}
