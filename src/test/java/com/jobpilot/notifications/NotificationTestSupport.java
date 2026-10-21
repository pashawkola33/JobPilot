package com.jobpilot.notifications;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.jobpilot.identity.*;import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import com.jobpilot.searchprofile.*;import com.jobpilot.personalizedmatching.*;import com.jobpilot.jobs.domain.*;
import java.time.*;import java.sql.Timestamp;import java.nio.file.*;import java.nio.file.attribute.PosixFilePermissions;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.test.context.*;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;import org.testcontainers.junit.jupiter.*;

@Testcontainers @org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties={"jobpilot.scheduled-tasks-enabled=false","jobpilot.telegram.bot-token="+TelegramInitDataFixture.BOT_TOKEN,"jobpilot.telegram.bot-username=JobPilotTestBot","jobpilot.identity.enabled=true","jobpilot.identity.registration-enabled=true","jobpilot.identity.legacy-owner-telegram-user-id=4242","jobpilot.identity.beta-allowed-user-ids=9002,9003","jobpilot.notifications.enabled=true"})
abstract class NotificationTestSupport {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withCreateContainerCmdModifier(command->command.getHostConfig().withMemory(268435456L).withNanoCPUs(1000000000L));
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
 @Autowired JdbcTemplate jdbc;@Autowired TelegramIdentityService identities;@Autowired NotificationSettingsService settings;@Autowired SearchProfileService profiles;@Autowired MatchingCatalogue catalogue;@Autowired com.jobpilot.jobs.repository.JobRepository jobs;@Autowired com.jobpilot.jobs.service.JobProcessor processor;@Autowired ObjectMapper json;@Autowired PlatformTransactionManager manager;@Autowired com.jobpilot.common.ApplicationLifecycleGate lifecycle;
 AuthenticatedCandidate principal;long candidate;Clock clock;Path approval;NotificationRuntimeGate gate;NotificationOutboxService outbox;NotificationDeliveryWorker worker;AtomicInteger accepted;
 @BeforeEach void setup()throws Exception {
  jdbc.execute("truncate notification_attempts,notification_digest_envelopes,notification_preferences,notification_discoveries,notification_connections,search_profile_state,search_profile_revisions,vacancy_fact_state,vacancy_fact_revisions,matching_catalogue_changes cascade");jdbc.update("update matching_catalogue_state set change_revision=0");
  var token=identities.exchange(TelegramInitDataFixture.launch(9002,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed()).accessToken();principal=identities.authenticate(token);candidate=principal.candidateId();
  profiles.save(principal,new SearchProfileService.Update(0L,UUID.randomUUID().toString(),json.readValue("{\"desiredTitles\":[\"Engineer\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Romania\"]}",SearchProfile.class)));
  settings.save(principal,new NotificationSettingsService.Update(0L,UUID.randomUUID().toString(),true,false,true,false,85,"UTC",null,null));
  jdbc.update("insert into notification_connections values(?,9002,9002,?,'CONNECTED',current_timestamp,null)",candidate,identities.credentialScope());
  clock=Clock.fixed(Instant.now(),ZoneOffset.UTC);approval=Files.createTempDirectory("notification-approval-").resolve("approval");accepted=new AtomicInteger();
  gate=new NotificationRuntimeGate(jdbc,new NotificationProperties(true),new org.springframework.mock.env.MockEnvironment().withProperty("jobpilot.notifications.approval-file",approval.toString()),lifecycle);
  outbox=new NotificationOutboxService(jdbc,manager,settings,gate,clock,catalogue);worker=worker((chat,html)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",123L,0);});arm();
 }
 NotificationDeliveryWorker worker(NotificationTransport transport){return new NotificationDeliveryWorker(jdbc,manager,settings,gate,clock,catalogue,transport);}
 void arm()throws Exception{var id=gate.operatorIdentity();Files.writeString(approval,id.epoch()+"\n"+id.nonce()+"\n");Files.setPosixFilePermissions(approval,PosixFilePermissions.fromString("rw-------"));}
 <T>T tx(java.util.function.Supplier<T> work){return new TransactionTemplate(manager).execute(s->work.get());}
 long vacancy(boolean discover,String eligibility,int score)throws Exception {
  long id=tx(()->{var j=jobs.saveAndFlush(new Job("notification-fixture",UUID.randomUUID().toString(),"https://example.org/"+UUID.randomUUID(),"Engineer <Synthetic>","Example & Co","Romania",RemoteType.REMOTE,"FULL_TIME","Required skills: SQL. Remote worldwide. No prior experience required.",clock.instant(),null,"hash","hash","hash",clock.instant()));catalogue.capture(j,null);if(discover)jdbc.update("insert into notification_discoveries values(?,(select change_revision from matching_catalogue_state where id=1),?)",j.getId(),Timestamp.from(clock.instant()));return j.getId();});
  match(id,eligibility,score);return id;
 }
 long match(long job,String eligibility,int score){return tx(()->{long result=jdbc.queryForObject("insert into candidate_match_results(candidate_id,job_id,search_profile_version,job_revision,extractor_version,scorer_version,eligibility,compatibility_score,recommendation_score,evidence_coverage,payload,published_at,computed_at) select ?,?,s.active_revision,f.job_revision,f.extractor_version,?,?,?,?,?, '{}',?,? from search_profile_state s,vacancy_fact_state f where s.candidate_id=? and f.job_id=? on conflict(candidate_id,job_id,search_profile_version,job_revision,extractor_version,scorer_version) do nothing returning id",Long.class,candidate,job,PersonalizedMatcher.VERSION,eligibility,score,eligibility.equals("INELIGIBLE")?null:score,90,Timestamp.from(clock.instant()),Timestamp.from(clock.instant()),candidate,job);jdbc.update("insert into candidate_match_current values(?,?,?) on conflict(candidate_id,job_id) do update set result_id=excluded.result_id",candidate,job,result);jdbc.update("update candidate_matching_state set phase='READY',event_cursor=(select change_revision from matching_catalogue_state where id=1) where candidate_id=?",candidate);return result;});}
 String state(long id){return jdbc.queryForObject("select state from notification_outbox where candidate_id=? and job_id=?",String.class,candidate,id);}
}
