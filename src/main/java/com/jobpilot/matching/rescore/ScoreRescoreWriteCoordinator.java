package com.jobpilot.matching.rescore;

import org.springframework.stereotype.Service;

/** Validates all process guards before the transactional writer can be invoked. */
@Service
public class ScoreRescoreWriteCoordinator {
    private final ScoreRescoreWriteTransaction transaction;

    public ScoreRescoreWriteCoordinator(ScoreRescoreWriteTransaction transaction) {
        this.transaction = transaction;
    }

    public ScoreRescoreWriteResult execute(ScoreRescorePlan plan,
                                           ScoreRescoreCommandProperties properties) {
        ScoreRescoreCommandGuards.GuardValidation guards =
                ScoreRescoreCommandGuards.validateWrite(properties, plan);
        if (!guards.valid()) return ScoreRescoreWriteResult.error(guards.safeMessage());
        try {
            return transaction.apply(plan);
        } catch (RuntimeException aborted) {
            return ScoreRescoreWriteResult.error(
                    "Atomic score rescore transaction aborted and was rolled back");
        }
    }
}
