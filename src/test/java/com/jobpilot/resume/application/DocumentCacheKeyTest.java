package com.jobpilot.resume.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.resume.config.DocumentProperties;
import com.jobpilot.resume.domain.DocumentContactBlock;
import com.jobpilot.resume.domain.DocumentFormat;
import com.jobpilot.support.TestProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentCacheKeyTest {
    private static final String HMAC_KEY =
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Test
    void profileAnalysisTemplateRendererFormatMethodAndContactChangesInvalidateIdentity() {
        CandidateDocumentFacts candidate = ResumeTruthTestFixtures.candidate();
        JobDocumentFacts job = ResumeTruthTestFixtures.job("Java Spring Boot SQL");
        DocumentContactBlock contact = new DocumentContactBlock(
                "student@example.test", "", List.of());
        DocumentProperties firstProperties = properties("resume-v1", "renderer-v1");
        DocumentCacheKey first = new DocumentCacheKey(firstProperties,
                TestProperties.create(), new DocumentContactCacheIdentity(firstProperties));
        String baseline = first.resume(job, candidate,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false, contact);

        CandidateDocumentFacts changedProfile = new CandidateDocumentFacts(candidate.profileId(),
                2, "f".repeat(64), candidate.fullName(), candidate.location(),
                candidate.educationInstitution(), candidate.degree(), candidate.studyStartYear(),
                candidate.studyEndYear(), candidate.currentStudent(), candidate.finalYearStudent(),
                candidate.commercialExperienceYears(), candidate.skills(), candidate.languages(),
                candidate.projects());
        JobDocumentFacts changedAnalysis = new JobDocumentFacts(job.jobId(), job.title(), job.company(),
                job.location(), job.description(), job.descriptionHash(), job.analysisId() + 1,
                "e".repeat(64), job.analysis());

        assertThat(first.resume(job, changedProfile,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false, contact)).isNotEqualTo(baseline);
        assertThat(first.resume(changedAnalysis, candidate,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false, contact)).isNotEqualTo(baseline);
        assertThat(first.resume(job, candidate, Set.of(DocumentFormat.PDF), false, contact))
                .isNotEqualTo(baseline);
        assertThat(first.resume(job, candidate,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), true, contact)).isNotEqualTo(baseline);
        assertThat(first.resume(job, candidate,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false,
                new DocumentContactBlock("other@example.test", "", List.of())))
                .isNotEqualTo(baseline);
        DocumentProperties changedTemplate = properties("resume-v2", "renderer-v1");
        assertThat(new DocumentCacheKey(changedTemplate, TestProperties.create(),
                new DocumentContactCacheIdentity(changedTemplate)).resume(job, candidate,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false, contact))
                .isNotEqualTo(baseline);
        DocumentProperties changedRenderer = properties("resume-v1", "renderer-v2");
        assertThat(new DocumentCacheKey(changedRenderer, TestProperties.create(),
                new DocumentContactCacheIdentity(changedRenderer)).resume(job, candidate,
                Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), false, contact))
                .isNotEqualTo(baseline);
    }

    private DocumentProperties properties(String resumeTemplate, String renderer) {
        return new DocumentProperties(true, Path.of("data/documents"), 2_097_152,
                2_097_152, resumeTemplate, "cover-v1", renderer, 4_000,
                Duration.ofMinutes(10), HMAC_KEY, new DocumentProperties.Contact(
                "student@example.test", "", "", "", ""));
    }
}
