package com.jobpilot.llm.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.jobpilot.llm.domain.CandidateStrength;
import com.jobpilot.llm.domain.EvidenceReference;
import com.jobpilot.llm.domain.EvidenceSource;
import com.jobpilot.llm.domain.JobAnalysisData;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LlmStructuredResponseValidator {
    private static final Pattern PROHIBITED_OUTPUT = Pattern.compile(
            "(?is)(ignore\\s+(?:all\\s+)?previous\\s+instructions|print\\s+(?:the\\s+)?api\\s*key|"
                    + "invent\\s+(?:missing\\s+)?experience|unrestricted\\s+html|<script|authorization:\\s*bearer)");
    private static final Pattern UNSUPPORTED_CANDIDATE_ASSERTION = Pattern.compile(
            "(?is)\\b(?:candidate|they|he|she)\\s+(?:has|have|is|are|speaks?|knows?|uses?|"
                    + "worked|built|achieved|increased|improved|delivered|led|earned|holds?|"
                    + "studied|graduated)\\b|\\bcandidate(?:'s|’s).{0,100}\\b(?:native|fluent|"
                    + "commercial|professional|employer|degree|years?\\s+of\\s+experience)\\b|"
                    + "\\bworked\\s+at\\b|\\bemployed\\s+by\\b|"
                    + "\\bprofessional\\s+java\\s+developer\\b");
    private final ObjectReader strictReader;

    public LlmStructuredResponseValidator(ObjectMapper objectMapper) {
        strictReader = objectMapper.readerFor(JobAnalysisData.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    public JobAnalysisData validate(String json, JobAnalysisInput input) {
        final JobAnalysisData data;
        try {
            data = strictReader.readValue(json);
        } catch (JsonProcessingException exception) {
            throw invalid("LLM output is not valid canonical JSON");
        }
        validateStructure(data);
        validateTruth(data, input);
        return data;
    }

    private void validateStructure(JobAnalysisData data) {
        bounded(data.roleSummary(), 1, 500, "role summary");
        list(data.mustHaveRequirements(), 30, 500, "must-have requirements");
        list(data.preferredRequirements(), 30, 500, "preferred requirements");
        list(data.responsibilities(), 30, 500, "responsibilities");
        nullable(data.experienceRequirement(), 1000, "experience requirement");
        nullable(data.educationRequirement(), 1000, "education requirement");
        nullable(data.languageRequirement(), 1000, "language requirement");
        nullable(data.locationConstraints(), 1000, "location constraints");
        nullable(data.workAuthorizationSignals(), 1000, "work authorization signals");
        list(data.candidateGaps(), 30, 500, "candidate gaps");
        list(data.ambiguousRequirements(), 30, 500, "ambiguous requirements");
        if (data.candidateStrengths() == null || data.candidateStrengths().size() > 50
                || data.evidenceReferences() == null || data.evidenceReferences().isEmpty()
                || data.evidenceReferences().size() > 60
                || data.confidenceScore() < 0 || data.confidenceScore() > 100
                || data.deterministicFallbackUsed()) {
            throw invalid("LLM output is outside the canonical schema bounds");
        }
        rejectInjection(data.toString());
        if (UNSUPPORTED_CANDIDATE_ASSERTION.matcher(data.toString()).find()) {
            throw invalid("LLM output contains an unsupported candidate assertion");
        }
    }

    private void validateTruth(JobAnalysisData data, JobAnalysisInput input) {
        CandidateTruthSnapshot truth = input.candidateTruth();
        Map<String, CandidateTruthSnapshot.TruthFact> facts = truth == null
                ? Map.of() : truth.factsByKey();
        Set<String> strengthKeys = new HashSet<>();
        for (CandidateStrength strength : data.candidateStrengths()) {
            if (strength == null || strength.matchStrength() == null
                    || strength.factKey() == null || !facts.containsKey(strength.factKey())
                    || !strengthKeys.add(strength.factKey())) {
                throw invalid("LLM output contains an unsupported candidate fact");
            }
        }
        if (truth == null && !data.candidateStrengths().isEmpty()) {
            throw invalid("Generic analysis cannot contain candidate claims");
        }
        String vacancy = normalize(input.vacancyEvidenceText());
        Set<String> evidenceKeys = new HashSet<>();
        for (EvidenceReference evidence : data.evidenceReferences()) {
            validateEvidence(evidence, vacancy, facts);
            String unique = evidence.source() + "\u0000" + evidence.sourceKey() + "\u0000" + evidence.excerpt();
            if (!evidenceKeys.add(unique)) throw invalid("LLM output contains duplicate evidence");
        }
    }

    private void validateEvidence(EvidenceReference evidence, String vacancy,
                                  Map<String, CandidateTruthSnapshot.TruthFact> facts) {
        if (evidence == null || evidence.source() == null) throw invalid("Evidence is incomplete");
        bounded(evidence.sourceKey(), 1, 100, "evidence source key");
        bounded(evidence.excerpt(), 8, 300, "evidence excerpt");
        rejectInjection(evidence.excerpt());
        String excerpt = normalize(evidence.excerpt());
        if (evidence.source() == EvidenceSource.VACANCY) {
            if (!vacancy.contains(excerpt)) throw invalid("Vacancy evidence is unsupported");
            return;
        }
        CandidateTruthSnapshot.TruthFact fact = facts.get(evidence.sourceKey());
        if (fact == null || fact.source() != evidence.source()
                || !normalize(fact.verifiedText()).contains(excerpt)) {
            throw invalid("Candidate evidence is unsupported or strengthened");
        }
    }

    private void list(List<String> values, int maxItems, int maxLength, String label) {
        if (values == null || values.size() > maxItems) throw invalid("Invalid " + label);
        values.forEach(value -> bounded(value, 1, maxLength, label));
    }

    private void nullable(String value, int max, String label) {
        if (value != null) bounded(value, 1, max, label);
    }

    private void bounded(String value, int min, int max, String label) {
        String normalized = normalize(value);
        if (value == null || normalized.codePointCount(0, normalized.length()) < min
                || value.length() > max
                || value.indexOf('\u0000') >= 0) {
            throw invalid("Invalid " + label);
        }
    }

    private void rejectInjection(String value) {
        if (PROHIBITED_OUTPUT.matcher(value).find()) {
            throw invalid("LLM output repeated prohibited vacancy instructions");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private JobAnalysisValidationException invalid(String message) {
        return new JobAnalysisValidationException(message);
    }
}
