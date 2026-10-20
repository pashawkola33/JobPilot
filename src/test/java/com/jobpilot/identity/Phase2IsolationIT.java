package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real HTTP/service/PostgreSQL isolation; all identities, vacancies and notes are synthetic. */
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
class Phase2IsolationIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl);
        r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private String a, b;

    @BeforeEach void prepare() throws Exception {
        // Keep identities between cases but clear private state and consume a fresh signed launch.
        jdbc.update("delete from mini_app_mutation");
        jdbc.update("delete from application_status_history");
        jdbc.update("delete from applications");
        jdbc.update("delete from job_workflow_state");
        jdbc.update("update mini_app_state set mutation_revision=0");
        jdbc.update("delete from job_scores");
        jdbc.update("delete from jobs");
        for (int id : List.of(100, 101)) {
            jdbc.update("insert into jobs(id,source,external_id,canonical_url,title,company,location,description,fetched_at,first_seen_at,last_seen_at,status,raw_payload_hash,description_hash,normalized_fingerprint,screening_disposition,location_disposition,career_disposition,relevance_disposition) values(?,'fixture',? ,?,'Shared vacancy','Synthetic Company','Bucharest','Public vacancy description',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'NEW',repeat('a',64),repeat('b',64),repeat('c',64),'MATCH','MATCH','MATCH','MATCH')",
                    id, Integer.toString(id), "https://example.test/jobs/" + id);
            jdbc.update("insert into job_scores(job_id,score,band,suitable,formal_eligibility,java_backend,trainee_quality,supporting_technology,location_format,experience_compatibility,freshness,penalties,strengths,risks,hard_blockers,scored_at) values(?,99,'EXCELLENT_MATCH',true,20,20,10,5,10,10,5,0,'OWNER_PRIVATE_STRENGTH','OWNER_PRIVATE_RISK','',CURRENT_TIMESTAMP)", id);
        }
        a = login(9002); b = login(9003);
    }

    private String launch(long user) {
        return TelegramInitDataFixture.launch(user, Instant.now().getEpochSecond())
                .with("query_id", UUID.randomUUID().toString()).signed();
    }
    private String login(long user) throws Exception {
        return json.readTree(mvc.perform(post("/api/mini-app/v2/auth/telegram")
                .header("X-Telegram-Init-Data", launch(user))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("accessToken").asText();
    }
    private JsonNode ok(String token, MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString());
    }
    private JsonNode snapshot(String token) throws Exception { return ok(token, get("/api/mini-app/v2/snapshot")); }
    private JsonNode change(String token, long job, String state, String key) throws Exception {
        return ok(token, put("/api/mini-app/v2/jobs/" + job + "/workflow").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("status", state, "mutationId", key))));
    }
    private JsonNode change(String token, String state) throws Exception { return change(token, 100, state, UUID.randomUUID().toString()); }
    private long applicationId(long telegramUser) throws Exception {
        long id=ok(telegramUser==9002?a:b,get("/api/mini-app/v2/jobs/100/application")).path("applicationId").asLong();
        assertThat(id).isPositive();
        return id;
    }
    private void denied(String token, MockHttpServletRequestBuilder request, int expected) throws Exception {
        mvc.perform(request.header("Authorization", "Bearer " + token)).andExpect(status().is(expected));
    }

    @Test void sharedCatalogueNeverLeaksOwnersScoresOrEligibilityReasons() throws Exception {
        // A global rejection reflects the old owner's screening; it is not beta personalization.
        jdbc.update("update jobs set screening_disposition='REJECT', eligibility_reason='OWNER_PRIVATE_ELIGIBILITY' where id=101");
        for (String token : List.of(a,b)) {
            JsonNode page=snapshot(token);
            assertThat(page.path("reviewQueue").path("total").asInt()).isEqualTo(2);
            assertThat(page.path("reviewQueue").path("items")).hasSize(2);
            for (JsonNode job : page.path("reviewQueue").path("items")) {
                assertThat(job.path("score").asInt()).isZero();
                assertThat(job.path("band").isNull()).isTrue();
                assertThat(job.path("strengths")).isEmpty();
                assertThat(job.path("risks")).isEmpty();
            }
            assertThat(page.toString()).doesNotContain("OWNER_PRIVATE", "EXCELLENT_MATCH");
        }
        assertThat(jdbc.queryForObject("select count(*) from jobs", Integer.class)).isEqualTo(2);
    }

    @Test void saveDismissApplyAndUndoAreIndependentForOneSharedVacancy() throws Exception {
        JsonNode saved=change(a,"SAVED");
        assertThat(snapshot(a).path("saved").path("total").asInt()).isEqualTo(1);
        assertThat(snapshot(b).path("saved").path("total").asInt()).isZero();
        assertThat(snapshot(b).path("reviewQueue").path("total").asInt()).isEqualTo(2);
        change(b,"DISMISSED");
        JsonNode applied=change(a,"APPLIED");
        assertThat(snapshot(a).path("workflowCounts").path("applied").asInt()).isEqualTo(1);
        assertThat(snapshot(b).path("workflowCounts").path("dismissed").asInt()).isEqualTo(1);
        assertThat(snapshot(b).path("applications").path("total").asInt()).isZero();
        denied(b,post("/api/mini-app/v2/undo").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("mutationId","foreign-undo","undoToken",applied.path("undoToken").asText()))),409);
        ok(a,post("/api/mini-app/v2/undo").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("mutationId","own-undo","undoToken",applied.path("undoToken").asText()))));
        assertThat(snapshot(a).path("saved").path("total").asInt()).isEqualTo(1);
        assertThat(snapshot(b).path("workflowCounts").path("dismissed").asInt()).isEqualTo(1);
        assertThat(saved.path("mutationRevision").asInt()).isEqualTo(1);
    }

    @Test void applicationIdsNotesFollowUpsAndHistoryCannotCrossOwners() throws Exception {
        change(a,"SAVED"); change(b,"SAVED");
        long aid=applicationId(9002), bid=applicationId(9003);
        assertThat(aid).isNotEqualTo(bid);
        String base="/api/mini-app/v2/applications/";
        ok(a,put(base+aid+"/notes").contentType("application/json").content("{\"notes\":\"A_PRIVATE_NOTE\"}"));
        ok(a,put(base+aid+"/follow-up").contentType("application/json").content("{\"date\":\"2026-12-01\"}"));
        change(a,"APPLIED");
        assertThat(ok(a,get(base+aid)).path("notes").asText()).isEqualTo("A_PRIVATE_NOTE");
        assertThat(ok(b,get(base+bid)).path("notes").isNull()).isTrue();
        assertThat(ok(a,get(base+aid+"/history"))).hasSize(2);
        assertThat(ok(b,get(base+bid+"/history"))).hasSize(1);
        denied(b,get(base+aid),404); denied(b,get(base+aid+"/history"),404);
        denied(b,put(base+aid+"/notes").contentType("application/json").content("{\"notes\":\"attacker\"}"),404);
        denied(b,put(base+aid+"/follow-up").contentType("application/json").content("{\"date\":null}"),404);
        denied(b,put(base+aid+"/status").contentType("application/json").content("{\"status\":\"APPLIED\"}"),404);
        assertThat(ok(a,get(base+aid)).toString()).contains("A_PRIVATE_NOTE","2026-12-01");
        assertThat(snapshot(b).toString()).doesNotContain("A_PRIVATE_NOTE", "2026-12-01");
    }

    @Test void ownNoteEditInvalidatesOldUndoWithoutAffectingOtherAccounts() throws Exception {
        JsonNode saved=change(a,"SAVED");
        change(b,"SAVED");
        ok(a,put("/api/mini-app/v2/applications/"+applicationId(9002)+"/notes").contentType("application/json").content("{\"notes\":\"Keep this new note\"}"));
        denied(a,post("/api/mini-app/v2/undo").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("mutationId","stale-note-undo","undoToken",saved.path("undoToken").asText()))),409);
        assertThat(snapshot(a).path("saved").path("total").asInt()).isEqualTo(1);
        assertThat(snapshot(b).path("saved").path("total").asInt()).isEqualTo(1);
    }

    @Test void idempotencyKeysAndRevisionSequencesAreCandidateScoped() throws Exception {
        JsonNode ar=change(a,100,"SAVED","shared-key"), br=change(b,100,"DISMISSED","shared-key");
        assertThat(ar.path("mutationRevision").asInt()).isEqualTo(1);
        assertThat(br.path("mutationRevision").asInt()).isEqualTo(1);
        JsonNode replay=change(a,100,"SAVED","shared-key");
        assertThat(replay.path("replayed").asBoolean()).isTrue();
        denied(a,put("/api/mini-app/v2/jobs/100/workflow").contentType("application/json").content("{\"status\":\"DISMISSED\",\"mutationId\":\"shared-key\"}"),409);
        assertThat(snapshot(a).path("mutationRevision").asInt()).isEqualTo(1);
        assertThat(snapshot(b).path("mutationRevision").asInt()).isEqualTo(1);
    }

    @Test void concurrentMutationsHaveDistinctCommitOrderedRevisionsPerCandidate() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);
            var futures=new ArrayList<java.util.concurrent.Future<Long>>();
            for(int i=0;i<16;i++) {
                final int n=i;
                futures.add(pool.submit(()->{start.await();return change(a,100,"SAVED","parallel-"+n).path("mutationRevision").asLong();}));
            }
            start.countDown();
            var revisions=new HashSet<Long>();
            for(var future:futures) revisions.add(future.get(30,TimeUnit.SECONDS));
            assertThat(revisions).containsExactlyInAnyOrderElementsOf(java.util.stream.LongStream.rangeClosed(1,16).boxed().toList());
        }
        assertThat(snapshot(a).path("mutationRevision").asInt()).isEqualTo(16);
        assertThat(snapshot(b).path("mutationRevision").asInt()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from applications",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from application_status_history",Integer.class)).isEqualTo(1);
    }

    @Test void parallelSameKeyReplaysOnceWithoutDuplicateApplicationsOrHistory() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            var start=new CountDownLatch(1);
            var futures=new ArrayList<java.util.concurrent.Future<JsonNode>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{start.await();return change(a,100,"SAVED","one-key-1");}));
            start.countDown(); int fresh=0;
            for(var f:futures) { var response=f.get(30,TimeUnit.SECONDS);if(!response.path("replayed").asBoolean())fresh++;assertThat(response.path("mutationRevision").asInt()).isEqualTo(1); }
            assertThat(fresh).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("select count(*) from applications",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from application_status_history",Integer.class)).isEqualTo(1);
    }

    @Test void newSessionRestoresOnlyItsOwnPersistedStateAndLegacyRemainsOwnerOnly() throws Exception {
        change(a,"SAVED"); change(b,"DISMISSED");
        assertThat(snapshot(login(9002)).path("saved").path("total").asInt()).isEqualTo(1);
        assertThat(snapshot(login(9003)).path("saved").path("total").asInt()).isZero();
        mvc.perform(get("/api/mini-app/v1/snapshot").header("X-Telegram-Init-Data",launch(9002))).andExpect(status().isForbidden());
        String ownerLaunch=launch(4242);
        mvc.perform(get("/api/mini-app/v1/snapshot").header("X-Telegram-Init-Data",ownerLaunch))
                .andExpect(status().isOk()).andExpect(jsonPath("$.saved.total").value(0));
        mvc.perform(put("/api/mini-app/v1/jobs/100/workflow").header("X-Telegram-Init-Data",ownerLaunch).contentType("application/json").content("{\"status\":\"SAVED\",\"mutationId\":\"owner-save\"}"))
                .andExpect(status().isOk());
        assertThat(snapshot(b).path("saved").path("total").asInt()).isZero();
    }

    @Test void anonymousExpiredAndOwnershipParametersCannotReachPrivateState() throws Exception {
        mvc.perform(get("/api/mini-app/v2/snapshot")).andExpect(status().isUnauthorized());
        denied(a,get("/api/mini-app/v2/snapshot").param("candidateId","1"),400);
        denied(a,put("/api/mini-app/v2/jobs/100/workflow").param("candidateId","1").contentType("application/json").content("{\"status\":\"SAVED\",\"mutationId\":\"injected\"}"),400);
        jdbc.update("update auth_sessions set created_at=current_timestamp-interval '20 minutes', expires_at=current_timestamp-interval '5 minutes'");
        denied(a,get("/api/mini-app/v2/snapshot"),401);
        denied(a,put("/api/mini-app/v2/jobs/100/workflow").contentType("application/json").content("{\"status\":\"SAVED\",\"mutationId\":\"expired\"}"),401);
        assertThat(jdbc.queryForObject("select count(*) from applications",Integer.class)).isZero();
    }

    @Test void applicationStatusUsesJobBeforeApplicationLockAndPreservesConcurrentNote() throws Exception {
        change(a,"SAVED");
        long application=applicationId(9002);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        var request=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<JsonNode>>();
        try(var pool=Executors.newSingleThreadExecutor()) {
            // A legacy writer owns the job first, exactly as Telegram/JobReviewService do.
            tx.executeWithoutResult(transaction->{
                jdbc.queryForObject("select id from jobs where id=100 for update",Long.class);
                request.set(pool.submit(()->ok(a,put("/api/mini-app/v2/applications/"+application+"/status")
                        .contentType("application/json").content("{\"status\":\"APPLIED\"}"))));
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->{
                    jdbc.execute("select pg_stat_clear_snapshot()");
                    return jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock')",Boolean.class);
                });
                // The waiting HTTP operation must not already own the application lock.
                org.assertj.core.api.Assertions.assertThatCode(()->jdbc.queryForObject(
                        "select id from applications where id=? for update nowait",Long.class,application))
                        .doesNotThrowAnyException();
                jdbc.update("update applications set notes='Concurrent legacy note',version=version+1 where id=?",application);
            });
            assertThat(request.get().get(10,TimeUnit.SECONDS).path("application").path("status").asText()).isEqualTo("APPLIED");
        }
        assertThat(ok(a,get("/api/mini-app/v2/applications/"+application)).path("notes").asText()).isEqualTo("Concurrent legacy note");
        assertThat(snapshot(b).path("applications").path("total").asInt()).isZero();
    }
}
