package com.jobpilot.applications.application;

import java.util.List;

/** A bounded application projection with authoritative totals for Mini App reads. */
public record ApplicationSnapshotView(
        List<ApplicationView> items,
        long total,
        int limit,
        Counts counts) {

    public ApplicationSnapshotView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean truncated() {
        return total > items.size();
    }

    public record Counts(
            long total,
            long saved,
            long applied,
            long interview,
            long offer,
            long rejected,
            long withdrawn) {
    }
}
