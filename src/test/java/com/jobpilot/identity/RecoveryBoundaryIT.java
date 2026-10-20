package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.JobPilotApplication;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import com.jobpilot.telegram.api.TelegramClient;
import com.jobpilot.telegram.api.TelegramUpdate;
import com.jobpilot.telegram.review.TelegramReviewNotifier;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Starts actual application contexts repeatedly over one unchanged disposable database. */
@Testcontainers
class RecoveryBoundaryIT {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient HTTP=HttpClient.newHttpClient();
    private static final AtomicInteger SENDS=new AtomicInteger(), POLLS=new AtomicInteger();

    @TestConfiguration(proxyBeanMethods=false)
    static class SyntheticTelegram {
        @Bean @Primary TelegramClient recoveryTestClient() {
            return new TelegramClient() {
                public List<TelegramUpdate> getUpdates(Long offset,Duration timeout,int limit){POLLS.incrementAndGet();return List.of();}
                public void sendMessage(String chat,String html,List<List<Map<String,String>>> buttons){SENDS.incrementAndGet();}
                public void answerCallbackQuery(String id,String text){throw new AssertionError("No callbacks are sent in recovery rehearsal");}
            };
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class FailedRelease {
        @Bean SmartInitializingSingleton simulateReleaseStartupFailure() {
            return ()->{throw new IllegalStateException("Synthetic failed release after migration initialization");};
        }
    }
    private PostgreSQLContainer<?> database() {
        var db=new PostgreSQLContainer<>("postgres:16-alpine");db.start();return db;
    }
    private Flyway flyway(PostgreSQLContainer<?> db,String target,String... locations) {
        var config=Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword());
        if(target!=null)config.target(target);
        if(locations.length>0)config.locations(locations);
        return config.load();
    }
    private void seed(PostgreSQLContainer<?> db)throws Exception {
        flyway(db,"13").migrate();
        try(var c=connection(db)){LegacyMigrationFixture.seed(c);}
    }
    private Connection connection(PostgreSQLContainer<?> db)throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(),db.getUsername(),db.getPassword());
    }
    private ConfigurableApplicationContext start(PostgreSQLContainer<?> db,boolean recovery,boolean fail) {
        var sources=new ArrayList<Class<?>>(List.of(JobPilotApplication.class,SyntheticTelegram.class));
        if(fail)sources.add(FailedRelease.class);
        var args=new ArrayList<>(List.of("--spring.config.additional-location=classpath:fixtures/identity-legacy-profile.yml",
                "--spring.datasource.url="+db.getJdbcUrl(),"--spring.datasource.username="+db.getUsername(),"--spring.datasource.password="+db.getPassword(),
                "--server.address=127.0.0.1","--server.port=0","--jobpilot.identity.legacy-owner-telegram-user-id=4242",
                "--jobpilot.mini-app.allowed-user-ids=4242","--jobpilot.telegram.allowed-chat-ids=4242",
                "--jobpilot.telegram.bot-token="+TelegramInitDataFixture.BOT_TOKEN));
        if(recovery)args.add("--spring.profiles.active=recovery");
        else args.addAll(List.of("--jobpilot.scheduled-tasks-enabled=false","--jobpilot.telegram.enabled=true",
                "--jobpilot.telegram.commands-enabled=false","--jobpilot.mini-app.enabled=true",
                "--jobpilot.identity.enabled=true","--jobpilot.identity.registration-enabled=true",
                "--jobpilot.identity.beta-allowed-user-ids=9002,9003"));
        return new SpringApplication(sources.toArray(Class<?>[]::new)).run(args.toArray(String[]::new));
    }
    private String base(ConfigurableApplicationContext ctx){return "http://127.0.0.1:"+((ServletWebServerApplicationContext)ctx).getWebServer().getPort();}
    private String launch(long user){return TelegramInitDataFixture.launch(user,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed();}
    private HttpResponse<String> call(String base,String method,String path,String header,String credential,String body)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(10));
        if(header!=null)request.header(header,credential);
        if(body!=null)request.header("Content-Type","application/json");
        return HTTP.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode ok(String base,String method,String path,String token,String body)throws Exception {
        var response=call(base,method,path,"Authorization","Bearer "+token,body);
        assertThat(response.statusCode()).as("%s %s: %s",method,path,response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }
    private String login(String base,long user)throws Exception {
        var response=call(base,"POST","/api/mini-app/v2/auth/telegram","X-Telegram-Init-Data",launch(user),null);
        assertThat(response.statusCode()).isEqualTo(200);return JSON.readTree(response.body()).path("accessToken").asText();
    }
    private JsonNode change(String base,String token,int job,String status,String key)throws Exception {
        return ok(base,"PUT","/api/mini-app/v2/jobs/"+job+"/workflow",token,JSON.writeValueAsString(Map.of("status",status,"mutationId",key)));
    }
    private JsonNode searchProfile(String url,String token,long expected,String key,String title)throws Exception {
        return ok(url,"PUT","/api/mini-app/v2/me/search-profile",token,JSON.writeValueAsString(Map.of("expectedRevision",expected,"requestId",key,
                "profile",Map.of("desiredTitles",List.of(title),"targetRegions",List.of("Romania"),"workFormats",List.of("REMOTE")))));
    }
    private JsonNode snapshot(String base,String token)throws Exception{return ok(base,"GET","/api/mini-app/v2/snapshot",token,null);}
    private List<List<String>> rows(Connection c,String table,String where)throws SQLException {
        var result=new ArrayList<List<String>>();
        try(var s=c.createStatement();var r=s.executeQuery("select * from "+table+" "+where)) {
            while(r.next()){var row=new ArrayList<String>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.add(r.getString(i));result.add(row);}
        }
        result.sort(Comparator.comparing(Object::toString));return result;
    }
    private Map<String,List<List<String>>> betaRows(Connection c)throws SQLException {
        var result=new TreeMap<String,List<List<String>>>();
        for(String table:List.of("job_workflow_state","applications","application_status_history","mini_app_state","mini_app_mutation","telegram_identities","auth_sessions","telegram_auth_exchanges","resume_versions","cover_notes","search_profile_revisions","search_profile_state","candidate_match_results","candidate_match_current","candidate_matching_state"))
            result.put(table,rows(c,table,"where candidate_id in (select candidate_id from telegram_identities where telegram_user_id in (9002,9003))"));
        return result;
    }

    @Test void failedReleaseRecoversOwnerWithoutLosingBetaRowsOrReplayingNotifications()throws Exception {
        SENDS.set(0);POLLS.set(0);
        try(var db=database()) {
            seed(db);String a,b,oldOwner,foreignUndo;long betaApplication;
            Map<String,List<List<String>>> before;
            List<List<String>> deliveries,botState,legacyApps,legacyHistory;
            try(var c=connection(db);var normal=start(db,false,false)) {
                String url=base(normal);oldOwner=login(url,4242);a=login(url,9002);b=login(url,9003);
                searchProfile(url,a,0,"recovery-profile-a1","Backend engineer");
                searchProfile(url,a,1,"recovery-profile-a2","Platform engineer");
                searchProfile(url,b,0,"recovery-profile-b1","Designer");
                searchProfile(url,b,1,"recovery-profile-b2","Product designer");
                normal.getBean(com.jobpilot.personalizedmatching.MatchingCatalogue.class).bootstrap();
                for(int i=0;i<20;i++)normal.getBean(com.jobpilot.personalizedmatching.MatchingWorkService.class).runOne();
                normal.getBean(TelegramReviewNotifier.class).notifyIngestion(List.of(101L),List.of());
                assertThat(SENDS.get()).isEqualTo(1);
                change(url,oldOwner,103,"SAVED","owner-save-103");
                change(url,a,103,"SAVED","beta-a-save-103");
                change(url,a,103,"APPLIED","beta-a-apply-103");
                foreignUndo=change(url,b,103,"DISMISSED","beta-b-dismiss-103").path("undoToken").asText();
                change(url,b,102,"SAVED","beta-b-save-102");
                JsonNode applied=change(url,b,102,"APPLIED","beta-b-apply-102");
                ok(url,"POST","/api/mini-app/v2/undo",b,JSON.writeValueAsString(Map.of("mutationId","beta-b-undo-102","undoToken",applied.path("undoToken").asText())));
                long aid=ok(url,"GET","/api/mini-app/v2/jobs/103/application",a,null).path("applicationId").asLong();
                betaApplication=aid;
                ok(url,"PUT","/api/mini-app/v2/applications/"+aid+"/notes",a,"{\"notes\":\"Beta A private recovery note\"}");
                normal.getBean(com.jobpilot.personalizedmatching.MatchingCatalogue.class).bootstrap();
                for(int i=0;i<20;i++)normal.getBean(com.jobpilot.personalizedmatching.MatchingWorkService.class).runOne();
                before=betaRows(c);deliveries=rows(c,"telegram_job_delivery","");botState=rows(c,"telegram_bot_state","");
                legacyApps=rows(c,"applications","where id in (201,202)");legacyHistory=rows(c,"application_status_history","where id in (301,302)");
                assertThat(snapshot(url,a).path("workflowCounts").path("applied").asInt()).isEqualTo(1);
                assertThat(snapshot(url,b).path("workflowCounts").path("dismissed").asInt()).isEqualTo(1);
            }
            assertThatThrownBy(()->start(db,false,true)).hasStackTraceContaining("Synthetic failed release");
            try(var c=connection(db);var recovery=start(db,true,false)) {
                String url=base(recovery);
                assertThat(betaRows(c)).isEqualTo(before);
                assertThat(call(url,"GET","/api/mini-app/v2/snapshot","Authorization","Bearer "+a,null).statusCode()).isEqualTo(401);
                assertThat(call(url,"POST","/api/mini-app/v2/auth/telegram","X-Telegram-Init-Data",launch(9003),null).statusCode()).isEqualTo(403);
                assertThat(call(url,"GET","/api/mini-app/v2/me/search-profile","Authorization","Bearer "+a,null).statusCode()).isEqualTo(401);
                String owner=login(url,4242);
                assertThat(ok(url,"GET","/api/mini-app/v2/me/search-profile",owner,null).path("activeRevision").asLong()).isZero();
                searchProfile(url,owner,0,"recovery-owner-profile","Owner preference");
                assertThat(snapshot(url,oldOwner).path("applications").path("total").asInt()).isEqualTo(3);
                JsonNode own=snapshot(url,owner);
                assertThat(own.path("applications").path("total").asInt()).isEqualTo(3);
                assertThat(own.toString()).doesNotContain("Beta A private recovery note");
                var legacy=call(url,"PUT","/api/mini-app/v1/jobs/103/workflow","X-Telegram-Init-Data",launch(4242),"{\"status\":\"APPLIED\",\"mutationId\":\"recovery-owner-apply\"}");
                assertThat(legacy.statusCode()).isEqualTo(200);
                normalOwnerHasOnlyOwnData(url,owner);
                assertThat(call(url,"GET","/api/mini-app/v2/applications/"+betaApplication,"Authorization","Bearer "+owner,null).statusCode()).isEqualTo(404);
                assertThat(call(url,"GET","/api/mini-app/v2/applications/"+betaApplication+"/history","Authorization","Bearer "+owner,null).statusCode()).isEqualTo(404);
                assertThat(call(url,"POST","/api/mini-app/v2/undo","Authorization","Bearer "+owner,JSON.writeValueAsString(Map.of("mutationId","cross-recovery-undo","undoToken",foreignUndo))).statusCode()).isEqualTo(409);
                assertThat(recovery.getBean(com.jobpilot.personalizedmatching.MatchingCatalogue.class).bootstrap()).isZero();
                assertThat(recovery.getBean(com.jobpilot.personalizedmatching.MatchingWorkService.class).claim()).isNull();
                assertThat(recovery.getBean(com.jobpilot.personalizedmatching.MatchingWorkService.class).runOne()).isFalse();
                recovery.getBean(com.jobpilot.personalizedmatching.MatchingWorker.class).tick();
                recovery.getBean(TelegramReviewNotifier.class).notifyIngestion(List.of(101L),List.of());
                assertThat(SENDS.get()).isEqualTo(1);assertThat(POLLS.get()).isZero();
                assertThat(betaRows(c)).isEqualTo(before);
                assertThat(rows(c,"telegram_job_delivery","")).isEqualTo(deliveries);
                assertThat(rows(c,"telegram_bot_state","")).isEqualTo(botState);
                assertThat(rows(c,"applications","where id in (201,202)")).isEqualTo(legacyApps);
                assertThat(rows(c,"application_status_history","where id in (301,302)")).isEqualTo(legacyHistory);
            }
            try(var c=connection(db);var normal=start(db,false,false)) {
                assertThat(betaRows(c)).isEqualTo(before);
                normal.getBean(TelegramReviewNotifier.class).notifyIngestion(List.of(101L),List.of());
                assertThat(SENDS.get()).isEqualTo(1);assertThat(POLLS.get()).isZero();
                String url=base(normal),freshA=login(url,9002),freshB=login(url,9003);
                assertThat(ok(url,"GET","/api/mini-app/v2/me/search-profile",freshA,null).path("profile").path("desiredTitles").get(0).asText()).isEqualTo("Platform engineer");
                assertThat(ok(url,"GET","/api/mini-app/v2/me",freshA,null).path("onboardingRequired").asBoolean()).isFalse();
                assertThat(searchProfile(url,freshB,1,"recovery-profile-b2","Product designer").path("activeRevision").asLong()).isEqualTo(2);
                searchProfile(url,freshA,2,"recovery-profile-a3","Infrastructure engineer");
                assertThat(ok(url,"GET","/api/mini-app/v2/me/search-profile",freshB,null).path("activeRevision").asLong()).isEqualTo(2);
                assertThat(snapshot(url,freshA).path("workflowCounts").path("applied").asInt()).isEqualTo(1);
                assertThat(snapshot(url,freshB).path("saved").path("total").asInt()).isEqualTo(1);
                assertThat(snapshot(url,freshB).path("workflowCounts").path("dismissed").asInt()).isEqualTo(1);
                JsonNode replay=change(url,freshB,103,"DISMISSED","beta-b-dismiss-103");
                assertThat(replay.path("replayed").asBoolean()).isTrue();
                change(url,freshA,101,"SAVED","beta-a-resume-save");
                assertThat(snapshot(url,freshB).path("saved").path("total").asInt()).isEqualTo(1);
            }
            assertThat(flyway(db,null).validateWithResult().validationSuccessful).isTrue();
        }
    }
    private void normalOwnerHasOnlyOwnData(String url,String owner)throws Exception {
        assertThat(snapshot(url,owner).path("applications").path("total").asInt()).isEqualTo(3);
        assertThat(call(url,"GET","/internal/v1/applications",null,null,null).statusCode()).isEqualTo(403);
    }

    @Test void failedTransactionalFutureMigrationLeavesV19Recoverable()throws Exception {
        try(var db=database()) {
            seed(db);flyway(db,null).migrate();
            Path directory=Files.createTempDirectory("jobpilot-failed-migration-");
            try {
                Files.writeString(directory.resolve("V20__synthetic_failure.sql"),"create table failed_release_probe(id bigint); select cast('synthetic-failure' as integer);");
                assertThatThrownBy(()->flyway(db,null,"classpath:db/migration","filesystem:"+directory).migrate()).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
                try(var c=connection(db)) {assertThat(rows(c,"flyway_schema_history","where success=false")).isEmpty();}
                assertThat(flyway(db,null).info().current().getVersion().toString()).isEqualTo("19");
                try(var ctx=start(db,true,false)){assertThat(snapshot(base(ctx),login(base(ctx),4242)).path("applications").path("total").asInt()).isEqualTo(2);}
            } finally {Files.deleteIfExists(directory.resolve("V20__synthetic_failure.sql"));Files.deleteIfExists(directory);}
        }
    }

    @Test void committedUnknownSchemaIsRefusedRatherThanSilentlyDowngraded()throws Exception {
        try(var db=database()) {
            seed(db);flyway(db,null).migrate();
            Path directory=Files.createTempDirectory("jobpilot-future-schema-");
            try {
                Files.writeString(directory.resolve("V20__synthetic_future.sql"),"create table future_release_probe(id bigint);");
                flyway(db,null,"classpath:db/migration","filesystem:"+directory).migrate();
                assertThatThrownBy(()->start(db,true,false)).isInstanceOf(RuntimeException.class);
                assertThatThrownBy(()->start(db,false,false)).isInstanceOf(RuntimeException.class);
                try(var c=connection(db)){assertThat(rows(c,"applications","")).hasSize(2);}
                assertThat(flyway(db,null,"classpath:db/migration","filesystem:"+directory).info().current().getVersion().toString()).isEqualTo("20");
            } finally {Files.deleteIfExists(directory.resolve("V20__synthetic_future.sql"));Files.deleteIfExists(directory);}
        }
    }
}
