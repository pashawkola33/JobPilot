package com.jobpilot.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Turning off beta identity must not reopen private HTTP routes in a public Mini App runtime. */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "jobpilot.scheduled-tasks-enabled=false",
    "jobpilot.telegram.bot-token="+TelegramInitDataFixture.BOT_TOKEN,
    "jobpilot.mini-app.enabled=true", "jobpilot.mini-app.allowed-user-ids=4242,9002",
    "jobpilot.identity.enabled=false", "jobpilot.identity.registration-enabled=false",
    "jobpilot.identity.legacy-owner-telegram-user-id=4242"
})
class PublicMiniAppBoundaryIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired MockMvc mvc;
    @Test void disabledIdentityKeepsInternalDataPrivateAndLegacyOwnerUsable() throws Exception {
        mvc.perform(get("/api/mini-app/v2/snapshot")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/internal/v1/applications")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/resumes/1")).andExpect(status().isForbidden());
        mvc.perform(get("/api/sources/health")).andExpect(status().isForbidden());
        mvc.perform(get("/api/mini-app/v1/snapshot").header("X-Telegram-Init-Data",TelegramInitDataFixture.launch(4242,Instant.now().getEpochSecond()).signed()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/mini-app/v1/snapshot").header("X-Telegram-Init-Data",TelegramInitDataFixture.launch(9002,Instant.now().getEpochSecond()).signed()))
                .andExpect(status().isForbidden());
    }
}
