package com.jobpilot.llm.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobpilot.llm.application.JobAnalysisService;
import com.jobpilot.llm.domain.JobAnalysisResult;
import com.jobpilot.llm.domain.JobAnalysisResultStatus;
import com.jobpilot.llm.domain.LlmFailureCategory;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class JobAnalysisControllerTest {
    @Test
    void exposesOnlyTypedBoundedResultStatuses() throws Exception {
        JobAnalysisService service = org.mockito.Mockito.mock(JobAnalysisService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new JobAnalysisController(service)).build();
        when(service.analyze(42L, true)).thenReturn(new JobAnalysisResult(
                JobAnalysisResultStatus.BUDGET_EXCEEDED, 7L, 42L, 1, null,
                LlmFailureCategory.BUDGET_EXHAUSTED));

        mvc.perform(post("/internal/v1/jobs/42/analysis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.analysisId").value(7))
                .andExpect(jsonPath("$.failureCategory").value("BUDGET_EXHAUSTED"))
                .andExpect(jsonPath("$.prompt").doesNotExist())
                .andExpect(jsonPath("$.rawResponse").doesNotExist());
    }

    @Test
    void mapsCreatedAndMissingJobsWithoutLeakingExceptions() throws Exception {
        JobAnalysisService service = org.mockito.Mockito.mock(JobAnalysisService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new JobAnalysisController(service)).build();
        when(service.analyze(1L, false)).thenReturn(new JobAnalysisResult(
                JobAnalysisResultStatus.CREATED, 9L, 1L, null, null, null));
        when(service.analyze(2L, true)).thenReturn(new JobAnalysisResult(
                JobAnalysisResultStatus.JOB_NOT_FOUND, null, 2L, null, null, null));

        mvc.perform(post("/internal/v1/jobs/1/analysis").param("candidateSpecific", "false"))
                .andExpect(status().isCreated());
        mvc.perform(post("/internal/v1/jobs/2/analysis"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }
}
