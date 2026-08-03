package com.jobpilot.jobreview.domain;

/** UNREVIEWED is represented by the absence of a workflow row. */
public enum WorkflowStatus {
    UNREVIEWED,
    SAVED,
    APPLIED,
    DISMISSED;

    public boolean persisted() {
        return this != UNREVIEWED;
    }
}
