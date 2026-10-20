package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
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

@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "jobpilot.scheduled-tasks-enabled=false",
    "jobpilot.telegram.bot-token=" + TelegramInitDataFixture.BOT_TOKEN,
    "jobpilot.mini-app.enabled=true", "jobpilot.mini-app.allowed-user-ids=4242",
    "jobpilot.identity.enabled=true", "jobpilot.identity.registration-enabled=true",
    "jobpilot.identity.legacy-owner-telegram-user-id=4242", "jobpilot.identity.beta-allowed-user-ids=9002,9003"
})
class SearchProfileIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", DB::getJdbcUrl); r.add("spring.datasource.username", DB::getUsername);
        r.add("spring.datasource.password", DB::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TelegramIdentityService identities;
    private String a, b;
    private long candidateA;
    private static final String PATH="/api/mini-app/v2/me/search-profile";

    @BeforeEach void prepare() throws Exception {
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from information_schema.tables where table_name='search_profile_revisions')",Boolean.class)))
            jdbc.execute("truncate search_profile_state, search_profile_revisions cascade");
        a=login(9002); b=login(9003);
        candidateA=Long.parseLong(ok(a,get("/api/mini-app/v2/me")).path("accountId").asText());
    }
    private String login(long id) throws Exception {
        String launch=TelegramInitDataFixture.launch(id,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed();
        return json.readTree(mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",launch))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asText();
    }
    private JsonNode ok(String token,MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request.header("Authorization","Bearer "+token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
    }
    private ObjectNode profile(String title) {
        ObjectNode p=json.createObjectNode();p.putArray("desiredTitles").add(title);
        p.putArray("targetRegions").add("Romania");p.putArray("workFormats").add("REMOTE");return p;
    }
    private ObjectNode body(long expected,String requestId,JsonNode profile) {
        ObjectNode p=json.createObjectNode();p.put("expectedRevision",expected);p.put("requestId",requestId);p.set("profile",profile);return p;
    }
    private MockHttpServletRequestBuilder write(JsonNode body) {return put(PATH).contentType("application/json").content(body.toString());}
    private JsonNode save(String token,long expected,JsonNode profile) throws Exception {return ok(token,write(body(expected,UUID.randomUUID().toString(),profile)));}
    private long revision(String token) throws Exception {return ok(token,get(PATH)).path("activeRevision").asLong();}
    private void denied(String token,MockHttpServletRequestBuilder request,int statusCode) throws Exception {
        mvc.perform(request.header("Authorization","Bearer "+token)).andExpect(status().is(statusCode));
    }

    @Test void emptyProfileRequiresOnboardingAndOwnerWorkspaceRemainsAvailable() throws Exception {
        // Existing users may have earlier revisions; a new account is created in a separate transaction.
        String owner=login(4242);
        assertThat(ok(owner,get("/api/mini-app/v2/snapshot")).has("mutationRevision")).isTrue();
        JsonNode initial=ok(a,get(PATH));
        assertThat(initial.path("activeRevision").asLong()).isZero();
        {
            assertThat(initial.path("completionStatus").asText()).isEqualTo("NOT_STARTED");
            assertThat(initial.path("missingRequiredFields")).hasSize(3);
            assertThat(ok(a,get("/api/mini-app/v2/me")).path("onboardingRequired").asBoolean()).isTrue();
        }
        JsonNode draft=save(a,revision(a),json.createObjectNode());
        assertThat(draft.path("completionStatus").asText()).isEqualTo("INCOMPLETE");
        assertThat(ok(a,get("/api/mini-app/v2/me")).path("onboardingRequired").asBoolean()).isTrue();
    }
    @Test void twoUsersHaveDistinctProfilesAndCannotSelectForeignOwnership() throws Exception {
        long oldA=revision(a), oldB=revision(b);
        JsonNode first=save(a,oldA,profile("Backend developer")); save(b,oldB,profile("Product designer"));
        assertThat(ok(a,get(PATH)).path("profile").path("desiredTitles").get(0).asText()).isEqualTo("Backend developer");
        assertThat(ok(b,get(PATH)).path("profile").path("desiredTitles").get(0).asText()).isEqualTo("Product designer");
        assertThat(first.path("activeRevision").asLong()).isEqualTo(oldA+1);
        assertThat(first.path("completionStatus").asText()).isEqualTo("COMPLETE");
        assertThat(ok(a,get("/api/mini-app/v2/me")).path("accountState").asText()).isEqualTo("READY");
        assertThat(ok(a,get("/api/mini-app/v2/me")).path("onboardingRequired").asBoolean()).isFalse();
        denied(a,get(PATH).queryParam("candidateId",Long.toString(candidateA+1)),400);
        denied(a,get("/api/mini-app/v2/candidates/"+(candidateA+1)+"/search-profile"),404);
        ObjectNode foreign=body(oldA+1,UUID.randomUUID().toString(),profile("Attack"));foreign.put("candidateId",candidateA+1);
        denied(a,write(foreign),400);
        foreign.remove("candidateId");((ObjectNode)foreign.get("profile")).put("candidateId",candidateA+1);
        denied(a,write(foreign),400);
        assertThat(ok(b,get(PATH)).path("profile").path("desiredTitles").get(0).asText()).isEqualTo("Product designer");
    }
    @Test void credentialsFailClosedOnBothProfileMethods() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        for(String token:List.of("forged","z".repeat(43))) {
            denied(token,get(PATH),401); denied(token,write(body(0,UUID.randomUUID().toString(),profile("Test"))),401);
        }
        var principal=identities.authenticate(a);identities.revoke(principal);
        denied(a,get(PATH),401);denied(a,write(body(0,UUID.randomUUID().toString(),profile("Test"))),401);
        jdbc.update("update auth_sessions set created_at=current_timestamp-interval '2 minutes', expires_at=current_timestamp-interval '1 minute' where token_hash=?",identities.authenticate(b).sessionHash());
        denied(b,get(PATH),401);denied(b,write(body(0,UUID.randomUUID().toString(),profile("Test"))),401);
    }
    @Test void unknownFactsAreNeverInventedAndResumeTruthIsUnchanged() throws Exception {
        var before=jdbc.queryForList("select * from candidate_profiles order by id");
        var skills=jdbc.queryForList("select * from candidate_skills order by id");
        ObjectNode p=profile("Engineer");p.putArray("languages").addObject().put("language","English");
        JsonNode saved=save(a,revision(a),p);
        assertThat(saved.path("profile").path("workAuthorization").asText()).isEqualTo("UNKNOWN");
        assertThat(saved.path("profile").path("languages").get(0).path("proficiency").asText()).isEqualTo("UNKNOWN");
        assertThat(saved.path("profile").path("experienceLevel").asText()).isEqualTo("UNKNOWN");
        assertThat(saved.path("profile").path("salary").isNull()).isTrue();
        assertThat(saved.path("createdAt").asText()).isNotBlank();
        assertThat(jdbc.queryForList("select * from candidate_profiles order by id")).isEqualTo(before);
        assertThat(jdbc.queryForList("select * from candidate_skills order by id")).isEqualTo(skills);
    }
    @Test void validationRejectsInvalidOptionalFieldsAndOversizedOrUntrustedValues() throws Exception {
        long rev=revision(a);
        for(String salary:List.of("{\"amount\":0,\"currency\":\"EUR\",\"period\":\"YEAR\"}",
                "{\"amount\":1000,\"currency\":\"BAD\",\"period\":\"MONTH\"}",
                "{\"amount\":1000,\"currency\":\"EUR\"}",
                "{\"amount\":1000,\"currency\":\"EUR\",\"period\":\"MINUTE\"}")) {
            ObjectNode p=profile("Engineer");p.set("salary",json.readTree(salary));
            denied(a,write(body(rev,UUID.randomUUID().toString(),p)),400);
        }
        for(String field:List.of("desiredTitles","technicalSkills","targetRegions","languages")) {
            ObjectNode p=profile("Engineer");p.putArray(field).addNull();
            denied(a,write(body(rev,UUID.randomUUID().toString(),p)),400);
        }
        ObjectNode p=profile("x".repeat(121));denied(a,write(body(rev,UUID.randomUUID().toString(),p)),400);
        p=profile("Engineer");p.put("workAuthorization","GUESSED");denied(a,write(body(rev,UUID.randomUUID().toString(),p)),400);
        ObjectNode invalid=body(rev,UUID.randomUUID().toString(),profile("Engineer"));invalid.remove("expectedRevision");denied(a,write(invalid),400);
        p=profile("Engineer");p.putObject("salary").put("amount",3000).put("currency","EUR").put("period","MONTH");
        assertThat(save(a,rev,p).path("profile").path("salary").path("currency").asText()).isEqualTo("EUR");
    }
    @Test void malformedTypedValuesNeverBecomeUserQualificationsOrAuthorization() throws Exception {
        long rev=revision(a);
        for(String invalid:List.of("{\"workAuthorization\":1}","{\"workAuthorization\":\"1\"}","{\"experienceLevel\":5}",
                "{\"desiredTitles\":[123]}","{\"targetRegions\":[true]}","{\"workFormats\":[2]}","{\"experienceYears\":\"4\"}")) {
            ObjectNode p=profile("Engineer");p.setAll((ObjectNode)json.readTree(invalid));
            denied(a,write(body(rev,UUID.randomUUID().toString(),p)),400);
            assertThat(revision(a)).isEqualTo(rev);
        }
    }

    @Test void editsRetainHistoryAndStaleRevisionsConflict() throws Exception {
        long rev=revision(a);
        save(a,rev,profile("Before"));save(a,rev+1,profile("After"));
        denied(a,write(body(rev+1,UUID.randomUUID().toString(),profile("Stale"))),409);
        assertThat(ok(a,get(PATH)).path("profile").path("desiredTitles").get(0).asText()).isEqualTo("After");
        assertThat(jdbc.queryForObject("select count(*) from search_profile_revisions where candidate_id=? and revision in (?,?)",Long.class,candidateA,rev+1,rev+2)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select payload from search_profile_revisions where candidate_id=? and revision=?",String.class,candidateA,rev+1)).contains("Before");
    }
    @Test void duplicateSubmissionIsIdempotentButCannotReactivateOldRevision() throws Exception {
        long rev=revision(a);var request=body(rev,UUID.randomUUID().toString(),profile("Repeat"));
        JsonNode first=ok(a,write(request));assertThat(ok(a,write(request))).isEqualTo(first);
        assertThat(revision(a)).isEqualTo(rev+1);
        var different=request.deepCopy();different.set("profile",profile("Different"));denied(a,write(different),409);
        save(a,rev+1,profile("Newer"));denied(a,write(request),409);
        assertThat(revision(a)).isEqualTo(rev+2);
    }
    @Test void concurrentRequestsSerializeAndRepeatedTapActivatesOnlyOnce() throws Exception {
        long rev=revision(a);var body=body(rev,UUID.randomUUID().toString(),profile("Concurrent"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{start.await();return mvc.perform(write(body).header("Authorization","Bearer "+a)).andReturn().getResponse().getStatus();}));
            start.countDown();for(var result:results)assertThat(result.get(20,TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(revision(a)).isEqualTo(rev+1);
            CountDownLatch second=new CountDownLatch(1);results.clear();
            for(int i=0;i<2;i++) {final int n=i;results.add(pool.submit(()->{second.await();return mvc.perform(write(body(rev+1,UUID.randomUUID().toString(),profile("Writer "+n))).header("Authorization","Bearer "+a)).andReturn().getResponse().getStatus();}));}
            second.countDown();List<Integer> codes=new ArrayList<>();for(var result:results)codes.add(result.get(20,TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200,409);assertThat(revision(a)).isEqualTo(rev+2);
        }
    }
}
