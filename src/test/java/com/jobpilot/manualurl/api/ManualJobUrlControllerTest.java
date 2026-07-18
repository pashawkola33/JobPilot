package com.jobpilot.manualurl.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobpilot.manualurl.application.ManualJobUrlService;
import com.jobpilot.manualurl.domain.ManualJobStatus;
import com.jobpilot.manualurl.domain.ManualJobSubmissionResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManualJobUrlControllerTest {
    private final ManualJobUrlService service = mock(ManualJobUrlService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ManualJobUrlController(service)).build();

    @Test
    void returnsTypedCreatedContract() throws Exception {
        when(service.submit(eq("https://public.example/jobs/42"))).thenReturn(
                new ManualJobSubmissionResult(ManualJobStatus.CREATED, 42L,
                        "https://public.example/jobs/42", 80, List.of("Java match"),
                        List.of(), "Vacancy created."));

        mvc.perform(post("/internal/v1/jobs/manual-url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://public.example/jobs/42\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.jobId").value(42))
                .andExpect(jsonPath("$.score").value(80));
    }

    @Test
    void mapsInvalidUrlToTypedBadRequest() throws Exception {
        when(service.submit(eq(null))).thenReturn(ManualJobSubmissionResult.failure(
                ManualJobStatus.INVALID_URL, "The URL is invalid or targets a prohibited destination."));

        mvc.perform(post("/internal/v1/jobs/manual-url")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("INVALID_URL"))
                .andExpect(jsonPath("$.message").isString());
    }
}
