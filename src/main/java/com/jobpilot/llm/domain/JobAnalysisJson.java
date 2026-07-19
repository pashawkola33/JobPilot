package com.jobpilot.llm.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class JobAnalysisJson {
    private final ObjectMapper objectMapper;

    public JobAnalysisJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize validated job analysis");
        }
    }

    public List<String> readStrings(String value) {
        return read(value, new TypeReference<>() {});
    }

    public List<CandidateStrength> readStrengths(String value) {
        return read(value, new TypeReference<>() {});
    }

    public List<EvidenceReference> readEvidence(String value) {
        return read(value, new TypeReference<>() {});
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored job analysis is invalid");
        }
    }
}
