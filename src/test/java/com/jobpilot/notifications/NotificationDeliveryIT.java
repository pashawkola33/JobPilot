package com.jobpilot.notifications;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.jobpilot.identity.*;import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import com.jobpilot.searchprofile.*;import com.jobpilot.personalizedmatching.*;import com.jobpilot.jobs.domain.*;
import java.time.*;import java.sql.Timestamp;import java.nio.file.*;import java.nio.file.attribute.PosixFilePermissions;import java.util.*;import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.test.context.*;import org.springframework.transaction.PlatformTransactionManager;import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;import org.testcontainers.junit.jupiter.*;

class NotificationDeliveryIT extends NotificationTestSupport {
 @Test void onlyCurrentEligibleNewVacanciesAreAdmittedOnce()throws Exception{vacancy(false,"ELIGIBLE",95);long good=vacancy(true,"ELIGIBLE",95);vacancy(true,"UNKNOWN",99);vacancy(true,"INELIGIBLE",95);vacancy(true,"ELIGIBLE",84);assertThat(outbox.admitOne()).isTrue();outbox.admitOne();assertThat(jdbc.queryForList("select job_id from notification_outbox",Long.class)).containsExactly(good);assertThat(worker.deliverOne()).isTrue();assertThat(state(good)).isEqualTo("SENT");assertThat(accepted).hasValue(1);assertThat(worker.deliverOne()).isFalse();}
 @Test void unmatchedAndUnknownDiscoveriesAreRevisitedAfterMatchingPublishes()throws Exception{long job=vacancy(true,"UNKNOWN",95);outbox.admitOne();assertThat(jdbc.queryForObject("select count(*) from notification_outbox",Long.class)).isZero();tx(()->{var j=jobs.findById(job).orElseThrow();catalogue.capture(j,new RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),"Required skills: SQL. Remote worldwide. No prior experience required. Updated confirmed requirements.",j.getEmploymentType(),clock.instant(),null,"changed"));return null;});match(job,"ELIGIBLE",95);outbox.admitOne();outbox.admitOne();assertThat(jdbc.queryForList("select state from notification_outbox where candidate_id=? and job_id=?",String.class,candidate,job)).containsExactly("PENDING");}
 @Test void startupDisarmedAndOldApprovalCannotClaimOrWrite()throws Exception{vacancy(true,"ELIGIBLE",95);Files.delete(approval);assertThat(outbox.admitOne()).isFalse();assertThat(worker.deliverOne()).isFalse();assertThat(jdbc.queryForObject("select count(*) from notification_outbox",Long.class)).isZero();arm();var old=gate;gate=new NotificationRuntimeGate(jdbc,new NotificationProperties(true),new org.springframework.mock.env.MockEnvironment().withProperty("jobpilot.notifications.approval-file",approval.toString()),lifecycle);assertThat(old.permitted()).isTrue();assertThat(gate.permitted()).isFalse();}
 @Test void profileChangeAndResumeNeverImportHistoricalCatalogue()throws Exception{for(int i=0;i<110;i++)vacancy(false,"ELIGIBLE",95);outbox.admitOne();assertThat(jdbc.queryForObject("select count(*) from notification_outbox",Long.class)).isZero();long fresh=vacancy(true,"ELIGIBLE",95);outbox.admitOne();settings.save(principal,new NotificationSettingsService.Update(1L,UUID.randomUUID().toString(),true,true,true,false,85,"UTC",null,null));settings.save(principal,new NotificationSettingsService.Update(2L,UUID.randomUUID().toString(),true,false,true,false,85,"UTC",null,null));outbox.admitOne();assertThat(jdbc.queryForList("select state from notification_outbox where candidate_id=? and job_id=?",String.class,candidate,fresh)).containsExactly("CANCELED");assertThat(worker.deliverOne()).isFalse();assertThat(accepted).hasValue(0);}
 @Test void actualInsertOnlyAndLegacyCreatedPromotionDoesNotRediscover()throws Exception{String key=UUID.randomUUID().toString();var raw=new RawJob("notification-ingestion",key,"https://example.org/"+key,"Marketing assistant","Synthetic","Bucharest, Romania","Required skills: SQL. Remote worldwide. No prior experience required.","FULL_TIME",Instant.now(),null,"synthetic");var first=processor.process(raw);processor.process(raw);assertThat(jdbc.queryForObject("select count(*) from notification_discoveries where job_id=?",Long.class,first.job().getId())).isEqualTo(1);jdbc.execute("truncate notification_discoveries cascade");jdbc.update("update jobs set legacy_admission_pending=true where id=?",first.job().getId());var promoted=new RawJob(raw.source(),key,raw.url(),"Junior Java Developer","Synthetic",raw.location(),"Junior Java Developer. Required skills: Java. No prior experience required. Bucharest office.","FULL_TIME",Instant.now(),null,"synthetic changed");processor.process(promoted);assertThat(jdbc.queryForObject("select count(*) from notification_discoveries",Long.class)).isZero();}
 @Test void uncertainExternalAcceptanceAndOutcomeFailureAreNeverRetried()throws Exception{long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();var failing=worker((chat,text)->{accepted.incrementAndGet();throw new IllegalStateException("synthetic acceptance then connection loss");});assertThat(failing.deliverOne()).isTrue();assertThat(state(job)).isIn("UNCERTAIN","SENDING");worker.deliverOne();assertThat(accepted).hasValue(1);}

 @Test void secondCandidateCurrentUnknownResultCannotBorrowFirstCandidateEligibility()throws Exception{
  var second=identities.authenticate(identities.exchange(TelegramInitDataFixture.launch(9003,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed()).accessToken());
  profiles.save(second,new SearchProfileService.Update(0L,UUID.randomUUID().toString(),json.readValue("{\"desiredTitles\":[\"Engineer\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Romania\"]}",SearchProfile.class)));
  settings.save(second,new NotificationSettingsService.Update(0L,UUID.randomUUID().toString(),true,false,true,false,85,"UTC",null,null));jdbc.update("insert into notification_connections values(?,9003,9003,?,'CONNECTED',current_timestamp,null)",second.candidateId(),identities.credentialScope());
  long job=vacancy(true,"ELIGIBLE",95);long firstCandidate=candidate;candidate=second.candidateId();match(job,"UNKNOWN",99);candidate=firstCandidate;
  for(int n=0;n<4;n++)outbox.admitOne();assertThat(jdbc.queryForList("select candidate_id from notification_outbox",Long.class)).containsExactly(firstCandidate);worker=worker((chat,text)->{assertThat(chat).isEqualTo(9002);accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",123L,0);});assertThat(worker.deliverOne()).isTrue();assertThat(accepted).hasValue(1);
 }

 @Test void twoCandidatesReceiveDifferentOwnedEligibleVacancies()throws Exception {
  var second=identities.authenticate(identities.exchange(TelegramInitDataFixture.launch(9003,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed()).accessToken());
  profiles.save(second,new SearchProfileService.Update(0L,UUID.randomUUID().toString(),json.readValue("{\"desiredTitles\":[\"Engineer\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Romania\"]}",SearchProfile.class)));
  settings.save(second,new NotificationSettingsService.Update(0L,UUID.randomUUID().toString(),true,false,true,false,85,"UTC",null,null));
  jdbc.update("insert into notification_connections values(?,9003,9003,?,'CONNECTED',current_timestamp,null)",second.candidateId(),identities.credentialScope());
  long firstCandidate=candidate,jobA=vacancy(true,"ELIGIBLE",95),jobB=vacancy(true,"UNKNOWN",99);
  candidate=second.candidateId();match(jobA,"UNKNOWN",99);match(jobB,"ELIGIBLE",96);candidate=firstCandidate;
  for(int n=0;n<4;n++)outbox.admitOne();
  var sent=new HashMap<Long,String>();
  worker=worker((chat,text)->{assertThat(sent.put(chat,text)).as("one external call per recipient").isNull();return new NotificationTransport.Outcome("SENT","ACCEPTED",chat,0);});
  assertThat(worker.deliverOne()).isTrue();assertThat(worker.deliverOne()).isTrue();assertThat(worker.deliverOne()).isFalse();
  assertThat(sent.keySet()).containsExactlyInAnyOrder(9002L,9003L);
  String urlA=jobs.findById(jobA).orElseThrow().getCanonicalUrl(),urlB=jobs.findById(jobB).orElseThrow().getCanonicalUrl();
  assertThat(sent.get(9002L)).contains(urlA).doesNotContain(urlB);assertThat(sent.get(9003L)).contains(urlB).doesNotContain(urlA);
  assertThat(jdbc.queryForList("select candidate_id||':'||job_id||':'||state from notification_outbox",String.class)).containsExactlyInAnyOrder(firstCandidate+":"+jobA+":SENT",second.candidateId()+":"+jobB+":SENT");
  assertThat(jdbc.queryForObject("select count(*) from notification_attempt_items i join candidate_match_results r on r.id=i.result_id where i.candidate_id=r.candidate_id and i.job_id=r.job_id and r.eligibility='ELIGIBLE'",Long.class)).isEqualTo(2);
 }

 @Test void disabledIdentityRuntimeCannotDispatchExistingConsent()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();var policy=new IdentityProperties(false,true,4242L,List.of(9002L,9003L),null,null);
  var disabled=new NotificationSettingsService(jdbc,identities,policy,new NotificationProperties(true),new org.springframework.mock.env.MockEnvironment(),json,clock);
  var sender=new NotificationDeliveryWorker(jdbc,manager,disabled,gate,clock,catalogue,(chat,text)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",1L,0);});assertThat(sender.deliverOne()).isFalse();assertThat(state(job)).isEqualTo("CANCELED");assertThat(accepted).hasValue(0);
 }

 @Test void continuousNewDiscoveriesCannotSkipFreshJobWhoseEligibleMatchPublishesLater()throws Exception{
  long first=vacancy(true,"UNKNOWN",95);outbox.admitOne();
  tx(()->{var j=jobs.findById(first).orElseThrow();catalogue.capture(j,new RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),"Required skills: SQL. Remote worldwide. No prior experience required. Revised confirmed source.",j.getEmploymentType(),clock.instant(),null,"revised"));return null;});match(first,"ELIGIBLE",95);
  for(int n=0;n<120;n++)vacancy(true,"ELIGIBLE",95);outbox.admitOne();
  assertThat(jdbc.queryForList("select state from notification_outbox where candidate_id=? and job_id=?",String.class,candidate,first)).containsExactly("PENDING");
 }
 @Test void producerCannotAdvancePastUnpublishedMatchingProgress()throws Exception{
  vacancy(true,"ELIGIBLE",95);jdbc.update("update candidate_matching_state set event_cursor=0 where candidate_id=?",candidate);outbox.admitOne();
  assertThat(jdbc.queryForObject("select event_cursor from notification_preferences where candidate_id=?",Long.class,candidate)).isZero();assertThat(jdbc.queryForObject("select count(*) from notification_outbox",Long.class)).isZero();
 }

 @Test void quietCandidateCannotStarveAnotherCandidatesDueDelivery()throws Exception{
  var second=identities.authenticate(identities.exchange(TelegramInitDataFixture.launch(9003,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed()).accessToken());
  profiles.save(second,new SearchProfileService.Update(0L,UUID.randomUUID().toString(),json.readValue("{\"desiredTitles\":[\"Engineer\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Romania\"]}",SearchProfile.class)));settings.save(second,new NotificationSettingsService.Update(0L,UUID.randomUUID().toString(),true,false,true,false,85,"UTC",null,null));jdbc.update("insert into notification_connections values(?,9003,9003,?,'CONNECTED',current_timestamp,null)",second.candidateId(),identities.credentialScope());
  long job=vacancy(true,"ELIGIBLE",95);long firstCandidate=candidate;candidate=second.candidateId();match(job,"ELIGIBLE",95);candidate=firstCandidate;for(int n=0;n<4;n++)outbox.admitOne();jdbc.update("update notification_preferences set quiet_start='00:00',quiet_end='23:59' where candidate_id=?",firstCandidate);
  assertThat(worker.deliverOne()).isFalse();assertThat(worker.deliverOne()).isTrue();assertThat(accepted).hasValue(1);assertThat(jdbc.queryForObject("select state from notification_outbox where candidate_id=? and job_id=?",String.class,second.candidateId(),job)).isEqualTo("SENT");
 }
 @Test void blockedPrivateChatSuspendsAllRemainingCandidateWork()throws Exception{
  long first=vacancy(true,"ELIGIBLE",95);long second=vacancy(true,"ELIGIBLE",95);outbox.admitOne();worker=worker((chat,text)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("BLOCKED","PRIVATE_CHAT_BLOCKED",null,0);});assertThat(worker.deliverOne()).isTrue();assertThat(state(first)).isEqualTo("FAILED");assertThat(worker.deliverOne()).isFalse();assertThat(state(second)).isEqualTo("CANCELED");assertThat(accepted).hasValue(1);assertThat(settings.view(principal).pauseReasons()).contains("BLOCKED");
 }
}
