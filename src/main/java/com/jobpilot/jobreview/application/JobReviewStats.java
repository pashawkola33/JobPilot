package com.jobpilot.jobreview.application;

public record JobReviewStats(
        long unreviewedMatch,
        long unreviewedReview,
        long saved,
        long applied,
        long dismissed
) {
}
