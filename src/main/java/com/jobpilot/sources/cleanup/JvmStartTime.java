package com.jobpilot.sources.cleanup;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Stable identity boundary for the current application process. */
@Component
public class JvmStartTime {
    private final Instant value;

    public JvmStartTime() {
        this(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
    }

    JvmStartTime(Instant value) {
        this.value = value;
    }

    public Instant value() {
        return value;
    }
}
