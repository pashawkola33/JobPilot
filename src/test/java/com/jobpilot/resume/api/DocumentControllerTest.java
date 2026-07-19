package com.jobpilot.resume.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.resume.application.ApplicationDocumentSelectionException;
import com.jobpilot.resume.application.ApplicationDocumentSelectionResult;
import com.jobpilot.resume.application.ApplicationDocumentSelectionService;
import com.jobpilot.resume.application.DocumentDownload;
import com.jobpilot.resume.application.DocumentGenerationResult;
import com.jobpilot.resume.application.DocumentGenerationStatus;
import com.jobpilot.resume.application.GenerateDocumentsCommand;
import com.jobpilot.resume.application.ResumeGenerationService;
import com.jobpilot.resume.domain.DocumentFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DocumentControllerTest {
    private ResumeGenerationService generation;
    private ApplicationDocumentSelectionService selection;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        generation = mock(ResumeGenerationService.class);
        selection = mock(ApplicationDocumentSelectionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(generation, selection)).build();
    }

    @Test
    void generationReturnsOnlyTypedReviewMetadataWithoutPathsOrContacts() throws Exception {
        when(generation.generate(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(new DocumentGenerationResult(DocumentGenerationStatus.FALLBACK,
                        7, 11L, 12L, "SUMMARY\nVerified preview", List.of("Selected facts"),
                        List.of("Can discuss: Verified bullet."), true,
                        com.jobpilot.llm.domain.LlmFailureCategory.DISABLED));

        mvc.perform(post("/internal/v1/jobs/7/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"includeCoverNote":true,"formats":["DOCX","PDF"],
                                 "useLlmDrafting":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALLBACK"))
                .andExpect(jsonPath("$.resumeVersionId").value(11))
                .andExpect(jsonPath("$.coverNoteId").value(12))
                .andExpect(jsonPath("$.resumePreview").value("SUMMARY\nVerified preview"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("path"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("student@example.test"))));
        verify(generation).generate(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(new GenerateDocumentsCommand(true,
                        java.util.Set.of(DocumentFormat.DOCX, DocumentFormat.PDF), true)));
    }

    @Test
    void downloadUsesFixedTypeNoStoreAndServerGeneratedSafeFilename() throws Exception {
        when(generation.downloadResume(11L, DocumentFormat.PDF))
                .thenReturn(new DocumentDownload("%PDF-synthetic".getBytes(),
                        "application/pdf", "resume-11.pdf"));

        mvc.perform(get("/internal/v1/resumes/11/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("resume-11.pdf")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes("%PDF-synthetic".getBytes()));
    }

    @Test
    void humanSelectionIsTypedAndWrongJobFailureIsSanitized() throws Exception {
        when(selection.select(7L, 11L, 12L)).thenReturn(
                new ApplicationDocumentSelectionResult(3, 7, 11, 12L,
                        ApplicationStatus.SAVED, true));

        mvc.perform(put("/internal/v1/applications/7/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":11,\"coverNoteId\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationStatus").value("SAVED"))
                .andExpect(jsonPath("$.changed").value(true));

        when(selection.select(8L, 11L, 12L)).thenThrow(
                new ApplicationDocumentSelectionException(
                        ApplicationDocumentSelectionException.Category.WRONG_JOB,
                        "Selected documents must belong to the application job."));
        mvc.perform(put("/internal/v1/applications/8/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeVersionId\":11,\"coverNoteId\":12}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.category").value("WRONG_JOB"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("/private/"))));
    }
}
