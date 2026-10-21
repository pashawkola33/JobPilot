package com.jobpilot.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.identity.*;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers @AutoConfigureMockMvc
@SpringBootTest(properties={"jobpilot.scheduled-tasks-enabled=false","jobpilot.telegram.bot-token="+TelegramInitDataFixture.BOT_TOKEN,
 "jobpilot.telegram.bot-username=JobPilotTestBot","jobpilot.identity.enabled=true","jobpilot.identity.registration-enabled=true",
 "jobpilot.identity.legacy-owner-telegram-user-id=4242","jobpilot.identity.beta-allowed-user-ids=9002,9003","jobpilot.notifications.enabled=true"})
class NotificationSettingsIT {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc; @Autowired TelegramIdentityService identities; @Autowired NotificationSettingsService settings; @Autowired org.springframework.transaction.PlatformTransactionManager transactions; @Autowired IdentityProperties identity; @Autowired java.time.Clock clock; @Autowired com.jobpilot.config.JobPilotProperties app;
 String a,b; long candidate; static final String PATH="/api/mini-app/v2/me/notifications";
 @BeforeEach void setup() throws Exception {a=login(9002);b=login(9003);candidate=identities.authenticate(a).candidateId(); if(exists())jdbc.execute("truncate notification_preferences, notification_connections cascade");}
 boolean exists(){return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from information_schema.tables where table_name='notification_preferences')",Boolean.class));}
 String login(long user)throws Exception{return json.readTree(mvc.perform(post("/api/mini-app/v2/auth/telegram").header("X-Telegram-Init-Data",TelegramInitDataFixture.launch(user,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("accessToken").asText();}
 JsonNode ok(String token,MockHttpServletRequestBuilder request)throws Exception{return json.readTree(mvc.perform(request.header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());}
 ObjectNode update(long rev,boolean enabled){return json.createObjectNode().put("expectedRevision",rev).put("requestId",UUID.randomUUID().toString()).put("enabled",enabled).put("paused",false).put("immediate",true).put("digest",false).put("minimumScore",85).put("timezone","UTC").putNull("quietStart").putNull("quietEnd");}
 MockHttpServletRequestBuilder write(JsonNode node){return put(PATH).contentType("application/json").content(node.toString());}
 void denied(String token,MockHttpServletRequestBuilder req,int code)throws Exception{mvc.perform(req.header("Authorization","Bearer "+token)).andExpect(status().is(code));}
 @Test void defaultsAreDisabledWithoutCreatingConsent()throws Exception{JsonNode v=ok(a,get(PATH));assertThat(v.path("revision").asLong()).isZero();assertThat(v.path("preferences").path("enabled").asBoolean()).isFalse();assertThat(v.path("preferences").path("immediate").asBoolean()).isTrue();assertThat(v.path("connectionStatus").asText()).isEqualTo("DISCONNECTED");assertThat(jdbc.queryForObject("select count(*) from notification_preferences",Long.class)).isZero();}
 @Test void savesAreOwnedAndCurrentReplayCannotRestoreOldConsent()throws Exception{var u=update(0,true);JsonNode first=ok(a,write(u));assertThat(first.path("revision").asLong()).isEqualTo(1);assertThat(ok(a,write(u))).isEqualTo(first);assertThat(ok(b,get(PATH)).path("revision").asLong()).isZero();ok(a,write(update(1,false)));denied(a,write(u),409);assertThat(ok(a,get(PATH)).path("preferences").path("enabled").asBoolean()).isFalse();}
 @Test void selectorsUnknownFieldsAndWrongScalarTypesFailClosed()throws Exception{for(String field:List.of("candidateId","chatId","telegramUserId","token")){var u=update(0,true);u.put(field,"9003");denied(a,write(u),400);}var u=update(0,true);u.put("minimumScore","85");denied(a,write(u),400);denied(a,get(PATH).queryParam("candidateId","2"),400);denied(a,post(PATH+"/activation").contentType("application/json").content("{}"),400);denied(a,delete(PATH+"/connection").queryParam("chatId","9003"),400);}
 @Test void invalidPolicyBoundsFailWithoutWriting()throws Exception{for(String zone:List.of("+03:00","Bogus/Zone","GMT+03:00")){var u=update(0,true);u.put("timezone",zone);denied(a,write(u),400);}var u=update(0,true);u.put("quietStart","09:00");denied(a,write(u),400);u.put("quietEnd","09:00");denied(a,write(u),400);u=update(0,true);u.put("minimumScore",101);denied(a,write(u),400);u=update(0,true);u.put("immediate",false);denied(a,write(u),400);}
 @Test void ownerRemainsLegacyExclusive()throws Exception{String owner=login(4242);assertThat(ok(owner,get(PATH)).path("legacyManaged").asBoolean()).isTrue();denied(owner,write(update(0,true)),409);denied(owner,post(PATH+"/activation"),409);}
 @Test void challengeRequiresConsentAndDisconnectIsIdempotent()throws Exception{denied(a,post(PATH+"/activation"),409);ok(a,write(update(0,true)));var c=ok(a,post(PATH+"/activation"));assertThat(c.path("activationUrl").asText()).startsWith("https://t.me/JobPilotTestBot?start=notify_");assertThat(c.path("expiresAt").asText()).isNotBlank();var off=ok(a,delete(PATH+"/connection"));assertThat(off.path("preferences").path("enabled").asBoolean()).isFalse();assertThat(ok(a,delete(PATH+"/connection"))).isEqualTo(off);}
 @Test void explicitSessionRevokeDisablesEvenFromAnOlderAuthorizingSession()throws Exception{var old=identities.authenticate(a);ok(a,write(update(0,true)));String newer=login(9002);identities.revoke(old);assertThat(ok(newer,get(PATH)).path("pauseReasons").toString()).contains("CONSENT_REVOKED");assertThat(jdbc.queryForObject("select enabled from notification_preferences where candidate_id=?",Boolean.class,candidate)).isFalse();}
 @Test void duplicateFieldsAndAmbiguousScalarValuesAreRejected()throws Exception{
   String duplicate=update(0,true).toString().replace("\"enabled\":true","\"enabled\":false,\"enabled\":true");
   denied(a,put(PATH).contentType("application/json").content(duplicate),400);
   for(String value:List.of("1","\"true\"","null"))denied(a,put(PATH).contentType("application/json").content(update(0,true).toString().replace("\"enabled\":true","\"enabled\":"+value)),400);
 }
 @Test void profileEditRebaselinesAndCancelsUnsentOwnedWork()throws Exception{
   long profileRevision=ok(a,get("/api/mini-app/v2/me/search-profile")).path("activeRevision").asLong();
   var profile=json.createObjectNode();profile.putArray("desiredTitles").add("Engineer");profile.putArray("targetRegions").add("Romania");profile.putArray("workFormats").add("REMOTE");
   var request=json.createObjectNode().put("expectedRevision",profileRevision).put("requestId",UUID.randomUUID().toString());request.set("profile",profile);
   ok(a,put("/api/mini-app/v2/me/search-profile").contentType("application/json").content(request.toString()));profileRevision++;
   ok(a,write(update(0,true)));
   String external=UUID.randomUUID().toString();
   long job=jdbc.queryForObject("insert into jobs(source,external_id,canonical_url,title,company,location,description,fetched_at,first_seen_at,last_seen_at,status,raw_payload_hash,description_hash,normalized_fingerprint) values('fixture',?,'https://example.test/jobs/'||?,'Engineer','Synthetic','Romania','Synthetic',current_timestamp,current_timestamp,current_timestamp,'NEW',repeat('a',64),repeat('b',64),repeat('c',64)) returning id",Long.class,external,external);
   jdbc.update("insert into vacancy_fact_revisions values(?,1,'test',repeat('a',64),null,'{}',current_timestamp)",job);
   long result=jdbc.queryForObject("insert into candidate_match_results(candidate_id,job_id,search_profile_version,job_revision,extractor_version,scorer_version,eligibility,compatibility_score,recommendation_score,evidence_coverage,payload,published_at,computed_at) values(?,?,?,1,'test','test','ELIGIBLE',90,90,90,'{}',current_timestamp,current_timestamp) returning id",Long.class,candidate,job,profileRevision);
   long barrier=jdbc.queryForObject("select change_revision from matching_catalogue_state",Long.class)+10;
   jdbc.update("update matching_catalogue_state set change_revision=?",barrier);jdbc.update("insert into notification_discoveries values(?,?,current_timestamp)",job,barrier);
   jdbc.update("insert into notification_outbox(candidate_id,job_id,result_id,generation,state,next_attempt_at,expires_at,created_at) values(?,?,?,1,'PENDING',current_timestamp,current_timestamp+interval '1 day',current_timestamp)",candidate,job,result);
   request.put("expectedRevision",profileRevision).put("requestId",UUID.randomUUID().toString());ok(a,put("/api/mini-app/v2/me/search-profile").contentType("application/json").content(request.toString()));
   assertThat(jdbc.queryForObject("select baseline_revision from notification_preferences where candidate_id=?",Long.class,candidate)).isEqualTo(barrier);
   assertThat(jdbc.queryForObject("select state from notification_outbox where candidate_id=? and job_id=?",String.class,candidate,job)).isEqualTo("CANCELED");
   assertThat(jdbc.queryForObject("select reason from notification_outbox where candidate_id=? and job_id=?",String.class,candidate,job)).isEqualTo("PROFILE_CHANGED");
   ok(b,write(update(0,true)));long foreignCandidate=identities.authenticate(b).candidateId();
   assertThatThrownBy(()->jdbc.update("insert into notification_outbox(candidate_id,job_id,result_id,generation,state,next_attempt_at,expires_at,created_at) values(?,?,?,1,'PENDING',current_timestamp,current_timestamp+interval '1 day',current_timestamp)",foreignCandidate,job,result)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
   long attempt=jdbc.queryForObject("insert into notification_attempts(candidate_id,mode,fence,local_date,timezone,payload,created_at) values(?,'IMMEDIATE',1,current_date,'UTC','synthetic immutable envelope',current_timestamp) returning id",Long.class,candidate);
   assertThatThrownBy(()->jdbc.update("update notification_attempts set payload='changed' where id=?",attempt)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
   assertThatThrownBy(()->jdbc.update("delete from notification_attempts where id=?",attempt)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
   assertThatThrownBy(()->jdbc.update("insert into notification_connections(candidate_id,telegram_user_id,chat_id,credential_scope,status,activated_at) values(?,9002,9003,repeat('a',64),'CONNECTED',current_timestamp)",candidate)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }

 @Test void candidateLockContentionAbortsWithinTheConfiguredBoundWithoutWriting()throws Exception{
   try(var held=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())){
     held.setAutoCommit(false);try(var statement=held.prepareStatement("select id from candidates where id=? for update")){statement.setLong(1,candidate);try(var rows=statement.executeQuery()){assertThat(rows.next()).isTrue();}}
     long started=System.nanoTime();Throwable error=catchThrowable(()->settings.save(identities.authenticate(a),new NotificationSettingsService.Update(0L,UUID.randomUUID().toString(),true,false,true,false,85,"UTC",null,null)));
     long elapsed=java.time.Duration.ofNanos(System.nanoTime()-started).toMillis();
     assertThat(error).isInstanceOf(org.springframework.dao.DataAccessException.class);
     Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();assertThat(cause).isInstanceOf(java.sql.SQLException.class);assertThat(((java.sql.SQLException)cause).getSQLState()).isEqualTo("55P03");assertThat(elapsed).isBetween(700L,4000L);
     held.rollback();assertThat(jdbc.queryForObject("select count(*) from notification_preferences where candidate_id=?",Long.class,candidate)).isZero();
   }
 }

 <T>T tx(java.util.function.Supplier<T> work){var transaction=new org.springframework.transaction.support.TransactionTemplate(transactions);transaction.setTimeout(15);return transaction.execute(status->work.get());}
 NotificationSettingsService configured(boolean enabled,IdentityProperties policy){return new NotificationSettingsService(jdbc,identities,policy,new NotificationProperties(enabled),new org.springframework.mock.env.MockEnvironment(),json,clock);}
 @Test void disabledFeatureStillHonorsExplicitSaveAndDisconnect()throws Exception{
   ok(a,write(update(0,true)));var disabled=configured(false,identity);var principal=identities.authenticate(a);
   assertThat(tx(()->disabled.save(principal,new NotificationSettingsService.Update(1L,UUID.randomUUID().toString(),false,false,true,false,85,"UTC",null,null))).preferences().enabled()).isFalse();
   ok(a,write(update(2,true)));assertThat(tx(()->disabled.disconnect(principal)).preferences().enabled()).isFalse();
 }
 @Test void disabledFeatureAndClosedBetaStillHonorVerifiedPrivateStop()throws Exception{
   ok(a,write(update(0,true)));var closed=new IdentityProperties(true,false,4242L,List.of(),null,null);var disabled=configured(false,closed);
   var activation=new NotificationActivationService(jdbc,identities,disabled,closed,app,clock);
   var stop=new com.jobpilot.telegram.api.TelegramUpdate(1,new com.jobpilot.telegram.api.TelegramUpdate.TelegramMessage(1,new com.jobpilot.telegram.api.TelegramUpdate.TelegramUser(9002,false),new com.jobpilot.telegram.api.TelegramUpdate.TelegramChat(9002,"private"),"/notifications_off",clock.instant().getEpochSecond(),false),null);
   assertThat(tx(()->activation.process(stop))).isTrue();assertThat(jdbc.queryForObject("select enabled from notification_preferences where candidate_id=?",Boolean.class,candidate)).isFalse();
 }
 @Test void rebaselineDoesNotHoldCatalogueWhileWaitingForPreferenceLock()throws Exception{
   ok(a,write(update(0,true)));
   try(var held=java.sql.DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
     held.setAutoCommit(false);int blocker;try(var q=held.createStatement();var rows=q.executeQuery("select pg_backend_pid()")){rows.next();blocker=rows.getInt(1);}
     try(var q=held.prepareStatement("select candidate_id from notification_preferences where candidate_id=? for update")){q.setLong(1,candidate);try(var rows=q.executeQuery()){assertThat(rows.next()).isTrue();}}
     var candidateLocked=new java.util.concurrent.CountDownLatch(1);var backend=new java.util.concurrent.atomic.AtomicInteger();
     var work=pool.submit(()->{var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);tx.setTimeout(15);tx.executeWithoutResult(status->{NotificationLocks.bound(jdbc).lockCandidate(candidate);backend.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));candidateLocked.countDown();NotificationSettingsService.rebaseline(jdbc,candidate,"TEST_REBASELINE");});});
     assertThat(candidateLocked.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();boolean blocked=false;long deadline=System.nanoTime()+java.time.Duration.ofMillis(750).toNanos();
     while(System.nanoTime()<deadline){try(var q=held.prepareStatement("select ?=any(pg_blocking_pids(?))")){q.setInt(1,blocker);q.setInt(2,backend.get());try(var rows=q.executeQuery()){rows.next();blocked=rows.getBoolean(1);}}if(blocked)break;}
     assertThat(blocked).as("rebaseline reached the held preference lock").isTrue();
     try(var q=held.createStatement();var rows=q.executeQuery("select change_revision from matching_catalogue_state where id=1 for update nowait")){assertThat(rows.next()).isTrue();}
     held.rollback();work.get(3,java.util.concurrent.TimeUnit.SECONDS);
   }
 }

}
