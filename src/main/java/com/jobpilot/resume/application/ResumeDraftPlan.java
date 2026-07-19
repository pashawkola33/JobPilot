package com.jobpilot.resume.application;

import java.util.List;

/** A structured selection plan. It contains stable keys, never free-form candidate claims. */
public record ResumeDraftPlan(TitleStyle titleStyle, List<String> skillKeys,
                              List<String> projectKeys, List<String> bulletKeys,
                              List<String> languageKeys) {
    public ResumeDraftPlan {
        skillKeys = List.copyOf(skillKeys);
        projectKeys = List.copyOf(projectKeys);
        bulletKeys = List.copyOf(bulletKeys);
        languageKeys = List.copyOf(languageKeys);
    }

    public enum TitleStyle {
        BACKEND_STUDENT,
        FULL_STACK_STUDENT,
        SOFTWARE_STUDENT
    }
}
