package com.jobpilot.applications.application;

import com.jobpilot.applications.domain.ApplicationStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ApplicationTransitionPolicy {
    private final Map<ApplicationStatus, EnumSet<ApplicationStatus>> allowed =
            new EnumMap<>(ApplicationStatus.class);

    public ApplicationTransitionPolicy() {
        allowed.put(ApplicationStatus.SAVED,
                EnumSet.of(ApplicationStatus.APPLIED, ApplicationStatus.WITHDRAWN));
        allowed.put(ApplicationStatus.APPLIED,
                EnumSet.of(ApplicationStatus.INTERVIEW, ApplicationStatus.REJECTED,
                        ApplicationStatus.OFFER, ApplicationStatus.WITHDRAWN));
        allowed.put(ApplicationStatus.INTERVIEW,
                EnumSet.of(ApplicationStatus.INTERVIEW, ApplicationStatus.REJECTED,
                        ApplicationStatus.OFFER, ApplicationStatus.WITHDRAWN));
        allowed.put(ApplicationStatus.OFFER, EnumSet.of(ApplicationStatus.WITHDRAWN));
        allowed.put(ApplicationStatus.REJECTED, EnumSet.noneOf(ApplicationStatus.class));
        allowed.put(ApplicationStatus.WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class));
    }

    public boolean canCreate(ApplicationStatus requested) {
        return requested == ApplicationStatus.SAVED || requested == ApplicationStatus.APPLIED;
    }

    public boolean canTransition(ApplicationStatus current, ApplicationStatus requested) {
        return current == requested || allowed.get(current).contains(requested);
    }
}
