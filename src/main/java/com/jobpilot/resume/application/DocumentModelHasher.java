package com.jobpilot.resume.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.Hashing;
import com.jobpilot.resume.domain.CoverNoteDocumentModel;
import com.jobpilot.resume.domain.ResumeDocumentModel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DocumentModelHasher {
    private final ObjectMapper mapper;

    public DocumentModelHasher(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String hash(ResumeDocumentModel model) {
        Map<String, Object> nonPrivate = new LinkedHashMap<>();
        nonPrivate.put("fullName", model.fullName());
        nonPrivate.put("selectedRoleTitle", model.selectedRoleTitle());
        nonPrivate.put("professionalSummary", model.professionalSummary());
        nonPrivate.put("education", model.education());
        nonPrivate.put("skills", model.skills());
        nonPrivate.put("languages", model.languages());
        nonPrivate.put("projects", model.projects());
        nonPrivate.put("changeSummary", model.changeSummary());
        nonPrivate.put("interviewClaims", model.interviewClaims());
        nonPrivate.put("templateVersion", model.templateVersion());
        return hashJson(nonPrivate);
    }

    public String hash(CoverNoteDocumentModel model) {
        Map<String, Object> nonPrivate = new LinkedHashMap<>();
        nonPrivate.put("candidateName", model.candidateName());
        nonPrivate.put("roleTitle", model.roleTitle());
        nonPrivate.put("company", model.company());
        nonPrivate.put("salutation", model.salutation());
        nonPrivate.put("paragraphs", model.paragraphs());
        nonPrivate.put("closing", model.closing());
        nonPrivate.put("referencedCandidateFactKeys", model.referencedCandidateFactKeys());
        nonPrivate.put("referencedVacancyEvidence", model.referencedVacancyEvidence());
        nonPrivate.put("templateVersion", model.templateVersion());
        return hashJson(nonPrivate);
    }

    private String hashJson(Object value) {
        try {
            return Hashing.sha256(mapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Validated document content could not be serialized");
        }
    }
}
