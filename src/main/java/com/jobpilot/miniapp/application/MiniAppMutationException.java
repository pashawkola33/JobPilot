package com.jobpilot.miniapp.application;

/**
 * The conflicts that are expected outcomes of the P0-B protocol rather than server faults.
 *
 * <p>Each maps to a typed 409 so the client can react deterministically. None of them is a 500:
 * a stale Undo and a replayed key with a changed payload are both things a correct client can
 * provoke by racing, and answering with a generic error would leave it guessing.
 *
 * <p>Messages are operator-safe constants — never a token, id, SQL fragment or payload.
 */
public class MiniAppMutationException extends RuntimeException {
    public enum Category {
        /** Same mutation id, different operation. A client bug, never a retry. */
        IDEMPOTENCY_CONFLICT,
        /** The undo no longer describes current durable state, so reversing would destroy data. */
        UNDO_STALE
    }

    private final Category category;

    public MiniAppMutationException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public Category getCategory() {
        return category;
    }

    public static MiniAppMutationException idempotencyConflict() {
        return new MiniAppMutationException(Category.IDEMPOTENCY_CONFLICT,
                "That request id was already used for a different action.");
    }

    /**
     * One message for every reason an Undo is no longer valid — superseded, already reversed,
     * unknown token, or the durable state moved underneath it. The client's response is the
     * same in all four cases: reconcile.
     */
    public static MiniAppMutationException undoStale() {
        return new MiniAppMutationException(Category.UNDO_STALE,
                "That change can no longer be undone. Reloading the latest state.");
    }
}
