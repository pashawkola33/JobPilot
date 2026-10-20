package com.jobpilot.personalizedmatching;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.jobs.domain.*;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.searchprofile.*;
import com.jobpilot.identity.*;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties={"jobpilot.scheduled-tasks-enabled=false","jobpilot.identity.enabled=true","jobpilot.identity.registration-enabled=true","jobpilot.identity.legacy-owner-telegram-user-id=4242","jobpilot.identity.beta-allowed-user-ids=9002,9003","jobpilot.telegram.bot-token="+TelegramInitDataFixture.BOT_TOKEN})
class PersonalizedMatchingIT {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
 @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);}
 @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper json; @Autowired TelegramIdentityService identities;
 @Autowired SearchProfileService profiles; @Autowired MatchingCatalogue catalogue; @Autowired MatchingWorkService work;
 @Autowired com.jobpilot.jobs.service.JobProcessor processor;
 @Autowired JobRepository jobs; @Autowired PlatformTransactionManager manager;
 @Autowired org.springframework.test.web.servlet.MockMvc mvc;
 final Map<Long,String> tokens=new HashMap<>();
 AuthenticatedCandidate a,b; long job;
 @BeforeEach void prepare() throws Exception {
  jdbc.execute("truncate candidate_match_current,candidate_match_results,candidate_matching_state,matching_catalogue_changes,vacancy_fact_state,vacancy_fact_revisions,search_profile_state,search_profile_revisions cascade");
  jdbc.update("update matching_catalogue_state set change_revision=0");
  a=login(9002);b=login(9003);
  job=new TransactionTemplate(manager).execute(tx->{var j=jobs.saveAndFlush(new Job("matching-fixture",UUID.randomUUID().toString(),"https://example.org/"+UUID.randomUUID(),"Data analyst","Example","Romania",RemoteType.REMOTE,"FULL_TIME","Required skills: SQL. Remote worldwide. No prior experience required.",Instant.now(),null,"hash","hash","hash",Instant.now()));catalogue.capture(j,null);return j.getId();});
  save(a,"Data analyst",0);save(b,"Marketing assistant",0);
 }
 AuthenticatedCandidate login(long user){String token=identities.exchange(TelegramInitDataFixture.launch(user,Instant.now().getEpochSecond()).with("query_id",UUID.randomUUID().toString()).signed()).accessToken();tokens.put(user,token);return identities.authenticate(token);}
 void save(AuthenticatedCandidate p,String title,long expected) throws Exception {profiles.save(p,new SearchProfileService.Update(expected,UUID.randomUUID().toString(),json.readValue("{\"desiredTitles\":[\""+title+"\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Romania\"]}",SearchProfile.class)));}
 @Test void retainedWorldwideRemoteUsesNeutralFactsWithoutChangingOwnerAdmission() throws Exception {
  var raw=new RawJob("matching-retained",UUID.randomUUID().toString(),"https://example.org/"+UUID.randomUUID(),"Marketing assistant","Example","Bucharest, Romania","Required skills: SQL. Remote worldwide. No prior experience required.","FULL_TIME",Instant.now(),null,"synthetic public vacancy");
  var processed=processor.process(raw);
  assertThat(processed.persisted()).isTrue();assertThat(processed.accepted()).isFalse();assertThat(processed.score()).isNull();
  assertThat(processed.job().getRemoteType()).isEqualTo(RemoteType.ONSITE);
  assertThat(processed.job().isLegacyAdmissionPending()).isTrue();
  var profile=json.readValue("{\"desiredTitles\":[\"Marketing assistant\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Germany\"],\"currentCountry\":\"Germany\",\"currentCity\":\"Berlin\",\"experienceYears\":0,\"relocation\":\"NO\"}",SearchProfile.class);
  profiles.save(b,new SearchProfileService.Update(1L,UUID.randomUUID().toString(),profile));
  for(int i=0;i<20;i++)work.runOne();
  var detail=api(9003,"/api/mini-app/v2/jobs/"+processed.job().getId(),200);
  assertThat(detail.path("facts").path("workFormat").asText()).isEqualTo("REMOTE");
  assertThat(detail.path("match").path("eligibility").asText()).isEqualTo("ELIGIBLE");
  assertThat(jdbc.queryForObject("select count(*) from job_scores where job_id=?",Long.class,processed.job().getId())).isZero();
  assertThat(jdbc.queryForObject("select count(*) from telegram_job_delivery where job_id=?",Long.class,processed.job().getId())).isZero();
 }
 @Test void ownedApiPaginationAndProfileInvalidationNeverUseGlobalScores() throws Exception {
  for(int x=0;x<2;x++)new TransactionTemplate(manager).executeWithoutResult(tx->{var j=jobs.saveAndFlush(new Job("fixture",UUID.randomUUID().toString(),"https://example.org/"+UUID.randomUUID(),"Data analyst","Example","Romania",RemoteType.REMOTE,"FULL_TIME","Required skills: SQL. Remote worldwide.",Instant.parse("2026-10-20T00:00:00Z"),null,"hash","hash","hash",Instant.now()));catalogue.capture(j,null);});
  for(int i=0;i<20;i++)work.runOne();
  var first=api(9002,"/api/mini-app/v2/matches?limit=1",200);
  assertThat(first.path("items")).hasSize(1);String cursor=first.path("nextCursor").asText();assertThat(cursor).isNotBlank();
  var second=api(9002,"/api/mini-app/v2/matches?limit=1&cursor="+cursor,200);
  assertThat(second.path("items").get(0).path("vacancy").path("id")).isNotEqualTo(first.path("items").get(0).path("vacancy").path("id"));
  api(9003,"/api/mini-app/v2/matches?limit=1&cursor="+cursor,400);
  api(9002,"/api/mini-app/v2/matches?candidateId="+b.candidateId(),400);
  api(9002,"/api/mini-app/v2/matches?limit=1&limit=2",400);
  var detail=api(9002,"/api/mini-app/v2/jobs/"+job,200);
  assertThat(detail.path("match").path("compatibilityScore").isInt()).isTrue();
  assertThat(detail.path("match").has("evidenceCoverage")).isTrue();
  save(a,"Finance assistant",1);
  assertThat(api(9002,"/api/mini-app/v2/jobs/"+job,200).path("match").isNull()).isTrue();
  assertThat(api(9002,"/api/mini-app/v2/matches",200).path("items")).isEmpty();
  api(9002,"/api/mini-app/v2/matches?limit=1&cursor="+cursor,409);
  assertThat(api(9003,"/api/mini-app/v2/jobs/"+job,200).path("match").isNull()).isFalse();
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/mini-app/v2/matches")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
 }
 com.fasterxml.jackson.databind.JsonNode api(long user,String url,int expected) throws Exception {
  var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization","Bearer "+tokens.get(user))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(expected)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control","no-store")).andReturn().getResponse();
  return json.readTree(response.getContentAsString());
 }
 @Test void boundedIdempotentBackfillAndSeparateCandidateResults() {
  for(int i=0;i<12;i++)work.runOne();
  assertThat(jdbc.queryForObject("select count(*) from candidate_match_current where job_id=?",Long.class,job)).isEqualTo(2);
  var scores=jdbc.queryForList("select compatibility_score from candidate_match_results where job_id=? order by candidate_id",Integer.class,job);
  assertThat(new HashSet<>(scores)).hasSize(2);
  long count=jdbc.queryForObject("select count(*) from candidate_match_results",Long.class);
  for(int i=0;i<4;i++)work.runOne();
  assertThat(jdbc.queryForObject("select count(*) from candidate_match_results",Long.class)).isEqualTo(count);
  assertThat(jdbc.queryForObject("select count(*) from telegram_job_delivery",Long.class)).isZero();
 }
 @Test void profileChangeAndExpiredLeaseFenceOldWorkers() throws Exception {
  var claim=work.claim();assertThat(claim).isNotNull();var batch=work.calculate(claim);
  var candidate=claim.candidateId()==a.candidateId()?a:b;
  save(candidate,"Logistics coordinator",1);
  assertThat(work.publish(batch)).isFalse();
  assertThat(jdbc.queryForObject("select count(*) from candidate_match_results where candidate_id=? and search_profile_version=1",Long.class,candidate.candidateId())).isZero();
  var next=work.claim();assertThat(next).isNotNull();var stale=work.calculate(next);
  jdbc.update("update candidate_matching_state set lease_until=current_timestamp-interval '1 second' where candidate_id=?",next.candidateId());
  assertThat(work.publish(stale)).isFalse();
 }
 @Test void changedFactsRejectOldPublicationAndKeepHistory() {
  var claim=work.claim();var batch=work.calculate(claim);
  new TransactionTemplate(manager).executeWithoutResult(tx->{Job j=jobs.findById(job).orElseThrow();RawJob raw=new RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),"Required skills: Python. Remote worldwide.",j.getEmploymentType(),j.getPublishedAt(),null,"changed");catalogue.capture(j,raw);});
  assertThat(work.publish(batch)).isFalse();
  assertThat(jdbc.queryForObject("select count(*) from vacancy_fact_revisions where job_id=?",Long.class,job)).isEqualTo(2);
 }
 @Test void claimsAreExclusiveAcrossConcurrentWorkersAndRestartReclaimsLease() throws Exception {
  var pool=Executors.newFixedThreadPool(4);
  try {var start=new CountDownLatch(1);var futures=new ArrayList<Future<MatchingWorkService.Claim>>();
   for(int i=0;i<4;i++)futures.add(pool.submit(()->{start.await();return work.claim();}));start.countDown();
   var claims=new ArrayList<MatchingWorkService.Claim>();for(var f:futures){var c=f.get(10,TimeUnit.SECONDS);if(c!=null)claims.add(c);}
   assertThat(claims.stream().map(MatchingWorkService.Claim::candidateId).distinct().count()).isEqualTo(claims.size());
   assertThat(claims).hasSize(2);
   var old=claims.getFirst();jdbc.update("update candidate_matching_state set lease_until=current_timestamp-interval '1 second' where candidate_id=?",old.candidateId());
   var resumed=work.claim();assertThat(resumed.candidateId()).isEqualTo(old.candidateId());assertThat(resumed.token()).isGreaterThan(old.token());
   assertThat(work.publish(work.calculate(old))).isFalse();assertThat(work.publish(work.calculate(resumed))).isTrue();
  } finally {pool.shutdownNow();}
 }
 @Test void eventBackfillIsBoundedAndRetriesStopAfterFiveAttempts() throws Exception {
  for(int x=0;x<60;x++)new TransactionTemplate(manager).executeWithoutResult(tx->{var j=jobs.saveAndFlush(new Job("bounded-fixture",UUID.randomUUID().toString(),"https://example.org/"+UUID.randomUUID(),"Data analyst","Example","Romania",RemoteType.REMOTE,"FULL_TIME","Required skills: SQL. Remote worldwide.",Instant.now(),null,"hash","hash","hash",Instant.now()));catalogue.capture(j,null);});
  work.runOne();work.runOne();var claim=work.claim();var batch=work.calculate(claim);
  assertThat(batch.results()).hasSize(25);assertThat(batch.eventCursor()-claim.eventCursor()).isEqualTo(25);assertThat(work.publish(batch)).isTrue();
  for(int i=0;i<20;i++)work.runOne();
  assertThat(jdbc.queryForObject("select count(*) from candidate_match_current",Long.class)).isEqualTo(122);
  jdbc.update("update candidate_matching_state set phase='NOT_READY' where candidate_id=?",b.candidateId());
  new TransactionTemplate(manager).executeWithoutResult(tx->{jdbc.queryForObject("select id from candidates where id=? for update",Long.class,a.candidateId());jdbc.update("insert into search_profile_revisions(candidate_id,revision,expected_revision,request_id,payload,completed,created_at) values(?,2,1,'bad-fixture-payload','{',true,current_timestamp)",a.candidateId());jdbc.update("update search_profile_state set active_revision=2 where candidate_id=?",a.candidateId());MatchingWorkService.activate(jdbc,a.candidateId(),2,true);});
  for(int i=0;i<5;i++){work.runOne();jdbc.update("update candidate_matching_state set retry_after=current_timestamp-interval '1 second' where candidate_id=?",a.candidateId());}
  assertThat(jdbc.queryForObject("select phase from candidate_matching_state where candidate_id=?",String.class,a.candidateId())).isEqualTo("FAILED");
  assertThat(jdbc.queryForObject("select attempts from candidate_matching_state where candidate_id=?",Integer.class,a.candidateId())).isEqualTo(5);
  assertThat(work.claim()).isNull();
 }
 @Test void semanticsChangesRecomputeAndLateProfileBatchCannotOverwriteNewResult() throws Exception {
  var oldClaim=work.claim();var oldBatch=work.calculate(oldClaim);
  var candidate=oldClaim.candidateId()==a.candidateId()?a:b;
  save(candidate,"Finance assistant",1);
  for(int i=0;i<20;i++)work.runOne();
  var before=jdbc.queryForList("select * from candidate_match_current order by candidate_id,job_id");
  assertThat(work.publish(oldBatch)).isFalse();
  assertThat(jdbc.queryForList("select * from candidate_match_current order by candidate_id,job_id")).isEqualTo(before);
  jdbc.update("update candidate_matching_state set extractor_version='old-facts',scorer_version='old-scorer' where candidate_id=?",candidate.candidateId());
  assertThat(work.claim()).isNull();
  work.initializeExistingProfiles();
  for(int i=0;i<20;i++)work.runOne();
  assertThat(jdbc.queryForObject("select extractor_version from candidate_matching_state where candidate_id=?",String.class,candidate.candidateId())).isEqualTo(VacancyFactExtractor.VERSION);
  assertThat(jdbc.queryForObject("select scorer_version from candidate_matching_state where candidate_id=?",String.class,candidate.candidateId())).isEqualTo(PersonalizedMatcher.VERSION);
  assertThat(jdbc.queryForObject("select count(*) from candidate_match_results where candidate_id=? and job_id=? and search_profile_version=2",Long.class,candidate.candidateId(),job)).isEqualTo(1);
 }
 @Test void expiredVacancyNeverExposesACurrentRecommendationBeforeWorkerRuns() throws Exception {
  for(int i=0;i<20;i++)work.runOne();
  assertThat(api(9002,"/api/mini-app/v2/jobs/"+job,200).path("match").isNull()).isFalse();
  jdbc.update("update jobs set deadline=current_timestamp-interval '1 second' where id=?",job);
  assertThat(api(9002,"/api/mini-app/v2/jobs/"+job,200).path("match").isNull()).isTrue();
  assertThat(api(9002,"/api/mini-app/v2/matches",200).path("items")).isEmpty();
 }
 @Test void legacyGlobalWorkflowStatusesNeverBecomeSharedVacancyFacts() throws Exception {
  for(String legacy:List.of("SAVED","APPLIED","INTERVIEW","REJECTED","OFFER","IGNORED")) {
   jdbc.update("update jobs set status=? where id=?",legacy,job);
   new TransactionTemplate(manager).executeWithoutResult(tx->catalogue.capture(jobs.findById(job).orElseThrow(),null));
   var detail=api(9003,"/api/mini-app/v2/jobs/"+job,200);
   assertThat(detail.path("facts").path("status").asText()).as(legacy).isEqualTo("OPEN");
   assertThat(detail.path("vacancy").path("workflowStatus").asText()).isEqualTo("UNREVIEWED");
  }
 }
 @Test void oldPersistedFactStatusIsSanitizedWithoutRewritingHistory() throws Exception {
  String oldPayload=jdbc.queryForObject("select payload from vacancy_fact_revisions where job_id=?",String.class,job);
  var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(oldPayload);legacy.put("status","APPLIED");String unsafe=legacy.toString();
  new TransactionTemplate(manager).executeWithoutResult(tx->{jdbc.update("insert into vacancy_fact_revisions(job_id,job_revision,extractor_version,source_hash,payload,created_at) values(?,2,?,'legacy-fixture',?,current_timestamp)",job,VacancyFactExtractor.VERSION,unsafe);jdbc.update("update vacancy_fact_state set job_revision=2 where job_id=?",job);});
  assertThat(api(9003,"/api/mini-app/v2/jobs/"+job,200).path("facts").path("status").asText()).isEqualTo("OPEN");
  assertThat(jdbc.queryForObject("select payload from vacancy_fact_revisions where job_id=? and job_revision=2",String.class,job)).isEqualTo(unsafe);
 }
 @Test void productionHistoryPreventsDestructiveJobDeletion() {
  assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(tx->{jobs.deleteById(job);jobs.flush();})).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThat(jdbc.queryForObject("select count(*) from vacancy_fact_revisions where job_id=?",Long.class,job)).isEqualTo(1);
 }
 @Test void neutralProviderFactsSurviveLaterStoredFieldBootstrap() {
  new TransactionTemplate(manager).executeWithoutResult(tx->{Job j=jobs.findById(job).orElseThrow();var raw=new RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),"Required skills: SQL. Remote in Germany only.",j.getEmploymentType(),j.getPublishedAt(),null,"provider");assertThat(catalogue.capture(j,raw)).isTrue();});
  new TransactionTemplate(manager).executeWithoutResult(tx->assertThat(catalogue.capture(jobs.findById(job).orElseThrow(),null)).isFalse());
  assertThat(jdbc.queryForObject("select count(*) from vacancy_fact_revisions where job_id=?",Long.class,job)).isEqualTo(2);
 }
 @Test void catalogueCursorCannotObserveLaterPublicationBeforeEarlierCommit() throws Exception {
  long before=jdbc.queryForObject("select change_revision from matching_catalogue_state where id=1",Long.class);
  long one=jobs.saveAndFlush(new Job("order-fixture","one","https://example.org/order-one","Data analyst","Example","Romania",RemoteType.REMOTE,"FULL_TIME","Remote worldwide.",Instant.now(),null,"hash","hash","hash",Instant.now())).getId();
  long two=jobs.saveAndFlush(new Job("order-fixture","two","https://example.org/order-two","Data analyst","Example","Romania",RemoteType.REMOTE,"FULL_TIME","Remote worldwide.",Instant.now(),null,"hash","hash","hash",Instant.now())).getId();
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var secondEntered=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try {
   var first=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{catalogue.capture(jobs.findById(one).orElseThrow(),null);entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");}catch(InterruptedException e){throw new RuntimeException(e);}}));
   assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
   var second=pool.submit(()->new TransactionTemplate(manager).executeWithoutResult(tx->{secondEntered.countDown();catalogue.capture(jobs.findById(two).orElseThrow(),null);}));
   assertThat(secondEntered.await(10,TimeUnit.SECONDS)).isTrue();
   assertThatThrownBy(()->second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   assertThat(jdbc.queryForObject("select change_revision from matching_catalogue_state where id=1",Long.class)).isEqualTo(before);
   release.countDown();first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
   assertThat(jdbc.queryForObject("select change_revision from matching_catalogue_state where id=1",Long.class)).isEqualTo(before+2);
  } finally {release.countDown();pool.shutdownNow();}
 }
 @Test void aCurrentV18RequestReplayKeepsItsIdentityAfterOptionalFieldsAreAdded() throws Exception {
  var legacy=json.createObjectNode();legacy.putArray("desiredTitles").add("Data analyst");legacy.putArray("targetRegions").add("Romania");legacy.putArray("workFormats").add("REMOTE");
  String oldPayload=legacy.toString();
  new TransactionTemplate(manager).executeWithoutResult(tx->{jdbc.queryForObject("select id from candidates where id=? for update",Long.class,a.candidateId());jdbc.update("insert into search_profile_revisions(candidate_id,revision,expected_revision,request_id,payload,completed,created_at) values(?,2,1,'pre-v19-replay',?,true,current_timestamp)",a.candidateId(),oldPayload);jdbc.update("update search_profile_state set active_revision=2 where candidate_id=?",a.candidateId());MatchingWorkService.activate(jdbc,a.candidateId(),2,true);});
  var view=profiles.save(a,new SearchProfileService.Update(1L,"pre-v19-replay",json.treeToValue(legacy,SearchProfile.class)));
  assertThat(view.activeRevision()).isEqualTo(2);
  assertThat(jdbc.queryForObject("select payload from search_profile_revisions where candidate_id=? and revision=2",String.class,a.candidateId())).isEqualTo(oldPayload);
 }
 @Test void databaseOwnershipAndImmutableHistoryAreEnforced() {
  for(int i=0;i<12;i++)work.runOne();
  assertThatThrownBy(()->jdbc.update("update candidate_match_results set compatibility_score=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
  Long id=jdbc.queryForObject("select id from candidate_match_results where candidate_id=? and job_id=?",Long.class,a.candidateId(),job);
  assertThatThrownBy(()->jdbc.update("update candidate_match_current set result_id=? where candidate_id=? and job_id=?",id,b.candidateId(),job)).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
}
