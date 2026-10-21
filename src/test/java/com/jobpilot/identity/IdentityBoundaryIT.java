package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "jobpilot.scheduled-tasks-enabled=false",
    "jobpilot.telegram.bot-token=" + TelegramInitDataFixture.BOT_TOKEN,
    "jobpilot.mini-app.enabled=true", "jobpilot.mini-app.allowed-user-ids=4242,9002,9003",
    "jobpilot.identity.enabled=true", "jobpilot.identity.registration-enabled=true",
    "jobpilot.identity.legacy-owner-telegram-user-id=4242",
    "jobpilot.identity.beta-allowed-user-ids=9002,9003"
})
class IdentityBoundaryIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TelegramIdentityService identities;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.jobpilot.config.JobPilotProperties properties;
    @Autowired com.jobpilot.miniapp.auth.TelegramInitDataValidator validator;
    @Autowired java.time.Clock clock;

    @BeforeEach void resetIdentityFixtures() {
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from information_schema.tables where table_name='telegram_identities')", Boolean.class))) {
            jdbc.execute("truncate telegram_auth_exchanges, auth_sessions, telegram_identities cascade");
            jdbc.update("delete from candidates where stable_key like 'account-%'");
        }
    }
    String launch(long user) { return launch(user, UUID.randomUUID().toString(), Instant.now().getEpochSecond()); }
    String launch(long user, String query, long epoch) {
        return TelegramInitDataFixture.launch(user, epoch).with("query_id", query).signed();
    }
    JsonNode exchange(long user) throws Exception {
        var body = mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data", launch(user)))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }
    JsonNode me(String token) throws Exception {
        return json.readTree(mvc.perform(get("/api/mini-app/v2/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString());
    }

    @Test void twoInvitedUsersGetDistinctAccountOnlyIdentities() throws Exception {
        JsonNode a=exchange(9002), b=exchange(9003);
        JsonNode ma=me(a.path("accessToken").asText()), mb=me(b.path("accessToken").asText());
        assertThat(ma.path("accountId").asText()).isNotBlank().isNotEqualTo(mb.path("accountId").asText());
        assertThat(ma.path("onboardingRequired").asBoolean()).isTrue();
        assertThat(ma.path("accountState").asText()).isEqualTo("ONBOARDING_REQUIRED");
        assertThat(ma.path("legacyOwner").asBoolean()).isFalse();
        assertThat(ma.fieldNames()).toIterable().containsExactlyInAnyOrder("accountId","accountState","onboardingRequired","legacyOwner","sessionExpiresAt");
        assertThat(jdbc.queryForObject("select count(*) from candidate_profiles", Integer.class)).isEqualTo(1);
    }
    @Test void onlyExplicitSignedOwnerBindsDefaultCandidate() throws Exception {
        exchange(9002); var owner=exchange(4242);
        assertThat(me(owner.path("accessToken").asText()).path("legacyOwner").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select c.stable_key from candidates c join telegram_identities t on c.id=t.candidate_id where t.telegram_user_id=4242",String.class)).isEqualTo("default");
        assertThat(jdbc.queryForObject("select c.stable_key from candidates c join telegram_identities t on c.id=t.candidate_id where t.telegram_user_id=9002",String.class)).startsWith("account-");
    }
    @Test void signedBetaUserCannotUseLegacyGlobalWorkflows() throws Exception {
        for(String path:List.of("/snapshot")) mvc.perform(get("/api/mini-app/v1"+path).header("X-Telegram-Init-Data",launch(9002))).andExpect(status().isForbidden());
        mvc.perform(put("/api/mini-app/v1/jobs/1/workflow").header("X-Telegram-Init-Data",launch(9002)).contentType("application/json").content("{\"status\":\"SAVED\",\"mutationId\":\"beta-mutation\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/mini-app/v1/undo").header("X-Telegram-Init-Data",launch(9002)).contentType("application/json").content("{\"mutationId\":\"beta-undo\",\"undoToken\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/mini-app/v1/snapshot").header("X-Telegram-Init-Data",launch(4242))).andExpect(status().isOk());
    }
    @Test void personalAndOperationalControllersAreNotPublicEvenWithSession() throws Exception {
        String token=exchange(9002).path("accessToken").asText();
        for(String path:List.of("/internal/v1/applications","/internal/v1/applications/1/history","/internal/v1/resumes/1","/internal/v1/resumes/1/pdf","/internal/v1/cover-notes/1","/internal/v1/operations/metrics","/api/sources/health"))
            mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        for(String path:List.of("/internal/v1/jobs/1/analysis","/internal/v1/jobs/1/documents","/internal/v1/jobs/manual-url"))
            mvc.perform(post(path).header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
    @Test void oneLaunchCannotBeExchangedTwiceOrReencodedToBypassReplay() throws Exception {
        String data=launch(9002);
        mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",data)).andExpect(status().isOk());
        mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",data.replace("%7B","%7b"))).andExpect(status().isConflict()).andExpect(jsonPath("$.category").value("AUTH_LAUNCH_REPLAYED"));
        assertThat(jdbc.queryForObject("select count(*) from auth_sessions",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentIndependentLaunchesCreateExactlyOneCandidateWithoutOrphans() throws Exception {
        var pool=Executors.newFixedThreadPool(20);var start=new CountDownLatch(1);
        long before=jdbc.queryForObject("select count(*) from candidates",Long.class);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<20;i++){String data=launch(9002);results.add(pool.submit(()->{start.await();return mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",data)).andReturn().getResponse().getStatus();}));}
            start.countDown();for(var f:results)assertThat(f.get(30,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from telegram_identities where telegram_user_id=9002",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from candidates",Long.class)).isEqualTo(before+1);
        } finally {pool.shutdownNow();}
    }
    @Test void concurrentReuseOfOneLaunchIssuesOnlyOneSession() throws Exception {
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);String data=launch(9002);
        try {
            List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<8;i++)results.add(pool.submit(()->{start.await();return mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",data)).andReturn().getResponse().getStatus();}));
            start.countDown();List<Integer> statuses=new ArrayList<>();for(var f:results)statuses.add(f.get(30,TimeUnit.SECONDS));
            assertThat(statuses).containsOnly(200,409);assertThat(statuses.stream().filter(x->x==200).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from auth_sessions",Integer.class)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }
    @Test void invalidExpiredFutureAndUninvitedLaunchesCreateNothing() throws Exception {
        for(String data:List.of("invalid",launch(9002,"old",Instant.now().minusSeconds(301).getEpochSecond()),launch(9002,"future",Instant.now().plusSeconds(90).getEpochSecond())))
            mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",data)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",launch(9999))).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from telegram_identities",Integer.class)).isZero();
    }
    @Test void tokensAreOpaqueHashedExpiringAndRevocable() throws Exception {
        String token=exchange(9002).path("accessToken").asText();assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject("select token_hash from auth_sessions",String.class)).hasSize(64).doesNotContain(token);
        me(token);jdbc.update("update auth_sessions set revoked_at=current_timestamp");
        mvc.perform(get("/api/mini-app/v2/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        String other=exchange(9003).path("accessToken").asText();
        jdbc.update("update auth_sessions set created_at=current_timestamp-interval '20 minutes', expires_at=current_timestamp-interval '5 minutes'");
        mvc.perform(get("/api/mini-app/v2/me").header("Authorization","Bearer "+other)).andExpect(status().isUnauthorized());
    }
    @Test void noAmbientOrUrlCredentialsOrClientSuppliedIdentityAreAccepted() throws Exception {
        mvc.perform(get("/api/mini-app/v2/me")).andExpect(status().isUnauthorized());
        String token=exchange(9002).path("accessToken").asText();
        mvc.perform(get("/api/mini-app/v2/me").param("access_token",token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mini-app/v2/auth/telegram").param("initData",launch(9003))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mini-app/v2/me").header("Authorization","Bearer "+token).param("candidateId","1")).andExpect(status().isBadRequest());
    }
    @Test void serviceRejectsForgedPrincipalAndRevokesRealSession() throws Exception {
        String token=exchange(9002).path("accessToken").asText();
        var principal=identities.authenticate(token);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->identities.me(new AuthenticatedCandidate(1,principal.telegramUserId(),principal.expiresAt(),principal.sessionHash())))
            .isInstanceOf(IdentityException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->identities.me(null)).isInstanceOf(IdentityException.class);
        identities.revoke(principal);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->identities.authenticate(token)).isInstanceOf(IdentityException.class);
    }
    @Test void rotationScopeAndUnknownNamespaceDoNotBypassAuthentication() throws Exception {
        String token=exchange(9002).path("accessToken").asText();
        mvc.perform(get("/api/mini-app/v2/other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mini-app/v2/other").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        jdbc.update("update auth_sessions set credential_scope=repeat('f',64)");
        mvc.perform(get("/api/mini-app/v2/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void ambiguousHeadersAndBodiesAreRejectedBeforeExchange() throws Exception {
        String data=launch(9002);
        mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",data,data)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",data).contentType("application/json").content("{\"candidateId\":1}")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from telegram_identities",Integer.class)).isZero();
    }
    @Test void changingSessionEpochDoesNotMakeSpentLaunchReusable() throws Exception {
        String data=launch(9002);
        mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",data)).andExpect(status().isOk());
        var rotated=new TelegramIdentityService(jdbc,new IdentityProperties(true,true,4242L,List.of(9002L),null,"rotated"),validator,properties,clock);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->tx.execute(s->rotated.exchange(data)))
            .isInstanceOfSatisfying(IdentityException.class,e->assertThat(e.category()).isEqualTo("AUTH_LAUNCH_REPLAYED"));
    }
}
