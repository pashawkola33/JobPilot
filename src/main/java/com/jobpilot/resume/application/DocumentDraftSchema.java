package com.jobpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DocumentDraftSchema {
    private final ObjectMapper mapper;

    public DocumentDraftSchema(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode resume(CandidateDocumentFacts facts) {
        List<String> skillKeys = facts.skills().stream().filter(CandidateDocumentFacts.SkillFact::active)
                .map(CandidateDocumentFacts.SkillFact::stableKey).toList();
        List<String> projectKeys = facts.projects().stream().filter(CandidateDocumentFacts.ProjectFact::active)
                .map(CandidateDocumentFacts.ProjectFact::stableKey).toList();
        List<String> bulletKeys = facts.projects().stream().filter(CandidateDocumentFacts.ProjectFact::active)
                .flatMap(project -> project.bullets().stream())
                .filter(CandidateDocumentFacts.BulletFact::active)
                .map(CandidateDocumentFacts.BulletFact::qualifiedKey).toList();
        List<String> languageKeys = facts.languages().stream()
                .filter(value -> value.active() && value.allowedInCv())
                .map(CandidateDocumentFacts.LanguageFact::stableKey).toList();
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("titleStyle", enumString(List.of(
                "BACKEND_STUDENT", "FULL_STACK_STUDENT", "SOFTWARE_STUDENT")));
        properties.put("skillKeys", array(skillKeys,
                Math.min(ResumeDraftBuilder.MIN_SKILLS, skillKeys.size()),
                Math.min(ResumeDraftBuilder.MAX_SKILLS, skillKeys.size())));
        properties.put("projectKeys", array(projectKeys, 1,
                Math.min(ResumeDraftBuilder.MAX_PROJECTS, projectKeys.size())));
        properties.put("bulletKeys", array(bulletKeys,
                Math.min(2, bulletKeys.size()), Math.min(12, bulletKeys.size())));
        properties.put("languageKeys", array(languageKeys,
                Math.min(2, languageKeys.size()),
                Math.min(ResumeDraftBuilder.MAX_LANGUAGES, languageKeys.size())));
        return object(properties, List.copyOf(properties.keySet()));
    }

    public JsonNode coverNote(SetValues allowed) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("candidateFactKeys", array(allowed.candidateFactKeys(),
                Math.min(6, allowed.candidateFactKeys().size()),
                Math.min(12, allowed.candidateFactKeys().size())));
        properties.put("vacancyEvidenceKeys", array(allowed.vacancyEvidenceKeys(), 2,
                allowed.vacancyEvidenceKeys().size()));
        return object(properties, List.copyOf(properties.keySet()));
    }

    private JsonNode object(Map<String, Object> properties, List<String> required) {
        return mapper.valueToTree(Map.of("type", "object", "additionalProperties", false,
                "properties", properties, "required", required));
    }

    private Map<String, Object> array(List<String> values, int minimum, int maximum) {
        return Map.of("type", "array", "minItems", minimum, "maxItems", maximum,
                "uniqueItems", true, "items", enumString(values));
    }

    private Map<String, Object> enumString(List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    public record SetValues(List<String> candidateFactKeys, List<String> vacancyEvidenceKeys) {
        public SetValues {
            candidateFactKeys = List.copyOf(candidateFactKeys);
            vacancyEvidenceKeys = List.copyOf(vacancyEvidenceKeys);
        }
    }
}
