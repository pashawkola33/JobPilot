package com.jobpilot.sources;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** JVM-local ownership evidence for source operations that have opened but not terminalized. */
@Component
public class SourceFetchExecutionRegistry {
    private final ConcurrentHashMap<Long, SourceFetchLogHandle> active = new ConcurrentHashMap<>();

    public void register(SourceFetchLogHandle handle) {
        // Registration must not create a new failure point after the RUNNING insert commits.
        active.put(handle.id(), handle);
    }

    public void unregister(SourceFetchLogHandle handle) {
        active.remove(handle.id(), handle);
    }

    public List<SourceFetchLogHandle> snapshot() {
        return active.values().stream()
                .sorted(Comparator.comparingLong(SourceFetchLogHandle::id))
                .toList();
    }
}
