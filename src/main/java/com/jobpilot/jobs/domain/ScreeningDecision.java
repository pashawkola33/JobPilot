package com.jobpilot.jobs.domain;

import java.util.ArrayList;
import java.util.List;

public record ScreeningDecision(
        ScreeningDisposition disposition,
        ScreeningDisposition locationDisposition,
        ScreeningDisposition careerDisposition,
        ScreeningDisposition relevanceDisposition,
        List<ScreeningReason> reasons) {

    public ScreeningDecision {
        locationDisposition = locationDisposition == null ? ScreeningDisposition.REVIEW : locationDisposition;
        careerDisposition = careerDisposition == null ? ScreeningDisposition.REVIEW : careerDisposition;
        relevanceDisposition = relevanceDisposition == null ? ScreeningDisposition.REVIEW : relevanceDisposition;
        ScreeningDisposition combined = ScreeningDisposition.combine(
                locationDisposition, careerDisposition, relevanceDisposition);
        disposition = combined;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static ScreeningDecision of(LocationEligibilityDecision location,
                                       EarlyCareerDecision career,
                                       RelevanceDecision relevance) {
        List<ScreeningReason> reasons = new ArrayList<>();
        if (location != null) reasons.addAll(location.reasons());
        if (career != null) reasons.addAll(career.reasons());
        if (relevance != null) reasons.addAll(relevance.reasons());
        ScreeningDisposition locationDisposition = location == null ? null : location.disposition();
        ScreeningDisposition careerDisposition = career == null ? null : career.disposition();
        ScreeningDisposition relevanceDisposition = relevance == null ? null : relevance.disposition();
        ScreeningDisposition finalDisposition = ScreeningDisposition.combine(
                locationDisposition, careerDisposition, relevanceDisposition);
        reasons.add(new ScreeningReason(ScreeningStage.FINAL, "FINAL_" + finalDisposition.name(),
                finalMessage(finalDisposition, location, career, relevance)));
        return new ScreeningDecision(finalDisposition, locationDisposition,
                careerDisposition, relevanceDisposition, reasons);
    }

    public static ScreeningDecision legacyMatch() {
        return new ScreeningDecision(ScreeningDisposition.MATCH,
                ScreeningDisposition.MATCH, ScreeningDisposition.MATCH,
                ScreeningDisposition.MATCH, List.of());
    }

    private static String finalMessage(ScreeningDisposition disposition,
                                       LocationEligibilityDecision location,
                                       EarlyCareerDecision career,
                                       RelevanceDecision relevance) {
        return switch (disposition) {
            case MATCH -> "All required screening stages matched";
            case REVIEW -> "No stage rejected the vacancy, but at least one requires review";
            case REJECT -> "Hard rejection: " + rejectionMessages(location, career, relevance);
        };
    }

    private static String rejectionMessages(LocationEligibilityDecision location,
                                            EarlyCareerDecision career,
                                            RelevanceDecision relevance) {
        List<String> messages = new ArrayList<>();
        if (location != null && location.disposition() == ScreeningDisposition.REJECT) {
            messages.addAll(location.reasons().stream().map(ScreeningReason::message).toList());
        }
        if (career != null && career.disposition() == ScreeningDisposition.REJECT) {
            messages.addAll(career.reasons().stream().map(ScreeningReason::message).toList());
        }
        if (relevance != null && relevance.disposition() == ScreeningDisposition.REJECT) {
            messages.addAll(relevance.reasons().stream().map(ScreeningReason::message).toList());
        }
        return messages.isEmpty()
                ? "an evaluated screening stage produced a hard rejection"
                : String.join("; ", messages);
    }
}
