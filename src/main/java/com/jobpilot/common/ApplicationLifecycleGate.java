package com.jobpilot.common;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

@Component
public class ApplicationLifecycleGate implements ApplicationListener<ContextClosedEvent> {
    private final AtomicBoolean acceptingWork = new AtomicBoolean(true);

    public boolean acceptingWork() {
        return acceptingWork.get();
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        acceptingWork.set(false);
    }
}
