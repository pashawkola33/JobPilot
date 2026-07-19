package com.jobpilot.llm.application;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.llm.domain.CandidateMatchStrength;
import com.jobpilot.llm.domain.CandidateStrength;
import com.jobpilot.llm.domain.EvidenceReference;
import com.jobpilot.llm.domain.EvidenceSource;
import com.jobpilot.llm.domain.JobAnalysisData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DeterministicJobAnalysisFallback {
    private static final Pattern INJECTION = Pattern.compile(
            "(?is)(ignore\\s+(?:all\\s+)?previous\\s+instructions|print\\s+(?:the\\s+)?api\\s*key|"
                    + "invent\\s+(?:missing\\s+)?experience|unrestricted\\s+html|<script)");
    public JobAnalysisData analyze(JobAnalysisInput input) {
        ExtractedRequirements requirements = input.deterministicRequirements();
        List<CandidateStrength> strengths = new ArrayList<>();
        List<EvidenceReference> evidence = new ArrayList<>();
        String vacancyText = input.vacancyEvidenceText();
        String vacancy = vacancyText.toLowerCase(Locale.ROOT);
        for (String technology : requirements.technologies()) {
            if (vacancy.contains(technology.toLowerCase(Locale.ROOT))) {
                String excerpt = meaningfulExcerpt(vacancyText, technology);
                if (excerpt != null) {
                    evidence.add(new EvidenceReference(EvidenceSource.VACANCY,
                            "job.description", excerpt));
                }
            }
        }
        CandidateTruthSnapshot truth = input.candidateTruth();
        if (truth != null) {
            for (CandidateTruthSnapshot.TruthFact fact : truth.facts()) {
                boolean matches = requirements.technologies().stream().anyMatch(technology ->
                        fact.verifiedText().toLowerCase(Locale.ROOT)
                                .contains(technology.toLowerCase(Locale.ROOT)));
                if (matches && strengths.size() < 20) {
                    strengths.add(new CandidateStrength(fact.key(), CandidateMatchStrength.MATCH));
                    String excerpt = boundedExact(fact.verifiedText(), 300);
                    if (normalizedLength(excerpt) >= 8) {
                        evidence.add(new EvidenceReference(fact.source(), fact.key(), excerpt));
                    }
                }
            }
        }
        if (evidence.isEmpty()) {
            String source = safeEvidence(input.title()) ? input.title()
                    : safeEvidence(input.company()) ? input.company()
                    : safeEvidence(input.location()) ? input.location()
                    : "Vacancy details were processed deterministically";
            String excerpt = boundedExact(source, 300);
            if (normalizedLength(excerpt) < 8) {
                excerpt = "Vacancy details were processed deterministically";
            }
            evidence.add(new EvidenceReference(EvidenceSource.VACANCY, "job.summary", excerpt));
        }
        List<String> gaps = gaps(requirements, truth);
        int confidence = Math.min(80, 45 + requirements.technologies().size() * 4);
        return new JobAnalysisData(
                bounded(safeRole(input.title()) + " at " + safeRole(input.company()), 500),
                requirements.technologies().stream().map(value -> "Technology: " + value).toList(),
                List.of(), List.of(),
                requirements.requiredExperienceYears() == null ? null
                        : "Minimum experience: " + requirements.requiredExperienceYears() + " years",
                requirements.requiredEducation(),
                requirements.spokenLanguages().isEmpty() ? null
                        : String.join("; ", requirements.spokenLanguages()),
                safeOptional(firstNonBlank(requirements.remoteEligibility(), requirements.location())),
                safeOptional(requirements.workAuthorization()), List.copyOf(strengths), gaps,
                ambiguous(requirements), List.copyOf(evidence), confidence, true);
    }

    private List<String> gaps(ExtractedRequirements requirements, CandidateTruthSnapshot truth) {
        if (truth == null) return List.of();
        List<String> result = new ArrayList<>();
        if (requirements.requiredExperienceYears() != null
                && java.math.BigDecimal.valueOf(requirements.requiredExperienceYears())
                .compareTo(truth.commercialJavaExperienceYears()) > 0) {
            result.add("Required experience exceeds verified commercial Java experience");
        }
        if (requirements.finalYearMandatory() && !truth.finalYearStudent()) {
            result.add("Final-year status is required but is not verified");
        }
        return List.copyOf(result);
    }

    private List<String> ambiguous(ExtractedRequirements requirements) {
        List<String> result = new ArrayList<>();
        if (requirements.requiredExperienceYears() == null) result.add("Experience requirement is unclear");
        if (requirements.remoteEligibility() == null
                || requirements.remoteEligibility().toLowerCase(Locale.ROOT).contains("unclear")) {
            result.add("Remote eligibility is unclear");
        }
        if (requirements.workAuthorization() == null) result.add("Work authorization is not stated");
        return List.copyOf(result);
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String boundedExact(String value, int maximum) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private String bounded(String value, int maximum) {
        String normalized = value == null ? "Unknown role" : value.strip();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
    }

    private String meaningfulExcerpt(String source, String needle) {
        int index = source.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        if (index < 0) return null;
        int start = Math.max(0, index - 40);
        int end = Math.min(source.length(), index + needle.length() + 80);
        String excerpt = boundedExact(source.substring(start, end).strip(), 300);
        return normalizedLength(excerpt) >= 8 && safeEvidence(excerpt) ? excerpt : null;
    }

    private int normalizedLength(String value) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return normalized.codePointCount(0, normalized.length());
    }

    private String safeRole(String value) {
        return safeEvidence(value) ? value.strip() : "Unspecified vacancy";
    }

    private boolean safeEvidence(String value) {
        return value != null && !value.isBlank() && !INJECTION.matcher(value).find();
    }

    private String safeOptional(String value) {
        return safeEvidence(value) ? value.strip() : null;
    }
}
