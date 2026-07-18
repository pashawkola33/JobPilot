package com.jobpilot.jobs.service;

import com.jobpilot.jobs.domain.Job;
import com.jobpilot.matching.ScoreCard;

public record JobProcessingResult(Job job, ScoreCard score, boolean newlyCreated) {
}
