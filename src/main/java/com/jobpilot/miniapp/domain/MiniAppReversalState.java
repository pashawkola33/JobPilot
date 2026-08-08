package com.jobpilot.miniapp.domain;

/** Whether a recorded Mini App mutation can still be undone, and if not, why. */
public enum MiniAppReversalState {
    /** The newest mutation for its job, not yet reversed. Carries an undo token. */
    REVERSIBLE,
    /** Already undone. Replaying its token must not undo a second time. */
    REVERSED,
    /** A newer Mini App mutation for the same job replaced it. */
    SUPERSEDED,
    /** Never reversible: an undo itself, or a mutation that changed nothing. */
    NOT_REVERSIBLE
}
