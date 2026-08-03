package com.jobpilot.jobreview.application;

import java.util.List;

public record JobQueuePage(
        JobQueue queue,
        List<JobQueueItem> items,
        int page,
        int size,
        long total
) {
    public JobQueuePage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasNext() {
        return (long) (page + 1) * size < total;
    }
}
