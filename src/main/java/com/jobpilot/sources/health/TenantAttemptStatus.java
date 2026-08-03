package com.jobpilot.sources.health;

/** Outcome of one ATS tenant fetch attempt. */
public enum TenantAttemptStatus {
    /** The provider answered normally and returned at least one vacancy. */
    SUCCESS,
    /** The provider answered normally but returned no vacancies. Still reachable. */
    EMPTY_SUCCESS,
    /** The attempt did not produce a usable response. */
    FAILURE;

    public boolean successful() {
        return this == SUCCESS || this == EMPTY_SUCCESS;
    }
}
