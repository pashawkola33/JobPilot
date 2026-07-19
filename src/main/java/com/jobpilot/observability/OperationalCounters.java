package com.jobpilot.observability;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

@Component
public class OperationalCounters {
    private final EnumMap<OperationalCounter, LongAdder> values =
            new EnumMap<>(OperationalCounter.class);

    public OperationalCounters() {
        for (OperationalCounter counter : OperationalCounter.values()) {
            values.put(counter, new LongAdder());
        }
    }

    public void increment(OperationalCounter counter) {
        add(counter, 1);
    }

    public void add(OperationalCounter counter, long amount) {
        if (counter != null && amount > 0) values.get(counter).add(amount);
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(
                key.name().toLowerCase(java.util.Locale.ROOT), value.sum()));
        return result;
    }
}
