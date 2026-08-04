package com.jobpilot.sources.cleanup;

import org.springframework.stereotype.Service;

/** Validates every independent guard before the atomic writer is invoked. */
@Service
public class SourceLogCleanupWriteCoordinator {
    private final SourceLogCleanupWriteTransaction transaction;

    public SourceLogCleanupWriteCoordinator(SourceLogCleanupWriteTransaction transaction) {
        this.transaction = transaction;
    }

    public SourceLogCleanupWriteResult execute(SourceLogCleanupPlan plan,
                                               SourceLogCleanupProperties properties) {
        SourceLogCleanupWriteGuards.Validation guards =
                SourceLogCleanupWriteGuards.validate(properties, plan);
        if (!guards.valid()) return SourceLogCleanupWriteResult.error(guards.safeReason());
        try {
            return transaction.apply(plan);
        } catch (RuntimeException aborted) {
            return SourceLogCleanupWriteResult.error("ATOMIC_TRANSACTION_ROLLED_BACK");
        }
    }
}
