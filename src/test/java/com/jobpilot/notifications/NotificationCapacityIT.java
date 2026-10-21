package com.jobpilot.notifications;
import static org.assertj.core.api.Assertions.*;import java.time.*;import java.sql.Timestamp;import java.util.*;import org.junit.jupiter.api.*;
class NotificationCapacityIT extends NotificationTestSupport {
 @Test void pendingCapacityAndHistoricalCatalogueRemainBoundedWithoutDroppedReplay()throws Exception{
  long started=System.nanoTime();for(int n=0;n<1001;n++)vacancy(false,"ELIGIBLE",95);for(int n=0;n<1001;n++)vacancy(true,"ELIGIBLE",95);
  List<Long> timings=new ArrayList<>();for(int n=0;n<24;n++){long tick=System.nanoTime();outbox.admitOne();timings.add(Duration.ofNanos(System.nanoTime()-tick).toMillis());}
  assertThat(jdbc.queryForObject("select count(*) from notification_outbox where state='PENDING'",Long.class)).isEqualTo(100);assertThat(jdbc.queryForObject("select count(*) from notification_outbox where state='DROPPED' and reason='CAPACITY_LIMIT'",Long.class)).isEqualTo(901);
  for(int n=0;n<12;n++)outbox.admitOne();assertThat(jdbc.queryForObject("select count(*) from notification_outbox",Long.class)).isEqualTo(1001);assertThat(jdbc.queryForObject("select count(*) from candidate_match_current where candidate_id=?",Long.class,candidate)).isEqualTo(2002);
  Collections.sort(timings);System.out.println("TASK2_CAPACITY catalogue=2002 pending=100 dropped=901 admissionP50ms="+timings.get(timings.size()/2)+" admissionP95ms="+timings.get(timings.size()-1)+" totalMs="+Duration.ofNanos(System.nanoTime()-started).toMillis()+" bytes="+jdbc.queryForObject("select pg_total_relation_size('notification_outbox')",Long.class)+" indexBytes="+jdbc.queryForObject("select pg_indexes_size('notification_outbox')",Long.class)+" containerMemory="+DB.getContainerInfo().getHostConfig().getMemory()+" containerNanoCpus="+DB.getContainerInfo().getHostConfig().getNanoCPUs());
 }
 @Test void threeImmediateAndOneTenItemDigestWithRollingBudgetAcrossTimezoneAndMidnight()throws Exception{
  clock=Clock.fixed(clock.instant().atZone(ZoneOffset.UTC).withHour(10).withMinute(0).withSecond(0).withNano(0).toInstant(),ZoneOffset.UTC);jdbc.update("update notification_preferences set digest=true");for(int n=0;n<20;n++)vacancy(true,"ELIGIBLE",95);outbox=new NotificationOutboxService(jdbc,manager,settings,gate,clock,catalogue);outbox.admitOne();
  for(int n=0;n<3;n++){worker=worker((chat,text)->{assertThat(text.length()).isLessThanOrEqualTo(4096);assertThat(text).doesNotContain("<Synthetic>","Example & Co");accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",123L,0);});assertThat(worker.deliverOne()).isTrue();clock=Clock.offset(clock,Duration.ofSeconds(61));}
  worker=worker((chat,text)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",124L,0);});assertThat(worker.deliverOne()).isTrue();assertThat(jdbc.queryForObject("select count(*) from notification_outbox where state='SENT'",Long.class)).isEqualTo(13);assertThat(accepted).hasValue(4);assertThat(worker.deliverOne()).isFalse();
  jdbc.update("update notification_preferences set timezone='Pacific/Kiritimati'");worker.deliverOne();assertThat(accepted).hasValue(4);
  clock=Clock.offset(clock,Duration.ofHours(13));worker=worker((chat,text)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",125L,0);});worker.deliverOne();assertThat(accepted).hasValue(4);
 }
 @Test void onlyDefinitive429RetriesAtMostFiveTimes()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();worker=worker((chat,text)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("RETRY","RATE_LIMITED",null,1);});
  for(int n=0;n<5;n++){assertThat(worker.deliverOne()).isTrue();clock=Clock.offset(clock,Duration.ofSeconds(2));worker=worker((chat,text)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("RETRY","RATE_LIMITED",null,1);});}
  assertThat(state(job)).isEqualTo("FAILED");assertThat(worker.deliverOne()).isFalse();assertThat(accepted).hasValue(5);assertThat(jdbc.queryForObject("select count(*) from notification_attempts",Long.class)).isEqualTo(5);
 }
 @Test void noEmptyDigestOrQuietHourSendAndDiscoveryExpires()throws Exception{
  jdbc.update("update notification_preferences set immediate=false,digest=true,quiet_start='00:00',quiet_end='23:59'");assertThat(worker.deliverOne()).isFalse();long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();assertThat(worker.deliverOne()).isFalse();clock=Clock.offset(clock,Duration.ofHours(25));worker=worker((chat,text)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",123L,0);});worker.deliverOne();assertThat(state(job)).isEqualTo("DROPPED");assertThat(accepted).hasValue(0);
 }
 @Test void tenHostilePublicCardsRemainValidBoundedHtml(){
  List<Map<String,Object>> items=new ArrayList<>();for(int n=0;n<10;n++)items.add(Map.of("title","<>&\"".repeat(100),"company","&".repeat(500),"location","<".repeat(500),"canonical_url","https://example.org/synthetic","compatibility_score",99));String html=NotificationDeliveryWorker.render(items,"DIGEST");assertThat(html.length()).isLessThanOrEqualTo(4096);assertThat(html).doesNotContain("javascript:","<>&");assertThat(html.split("Compatibility:",-1)).hasSize(11);
 }

 @Test void longAtsLinksRemainUsableAndTrackedMembershipExactlyMatchesRenderedCards()throws Exception{
  clock=Clock.fixed(clock.instant().atZone(ZoneOffset.UTC).withHour(10).withMinute(0).withSecond(0).withNano(0).toInstant(),ZoneOffset.UTC);jdbc.update("update notification_preferences set immediate=false,digest=true");String url="https://careers.example.org/jobs/"+"a".repeat(900)+"?lang=en&source=synthetic";
  for(int n=0;n<10;n++){long job=vacancy(true,"ELIGIBLE",95);jdbc.update("update jobs set canonical_url=? where id=?",url+"&job="+job,job);}
  outbox=new NotificationOutboxService(jdbc,manager,settings,gate,clock,catalogue);outbox.admitOne();java.util.concurrent.atomic.AtomicReference<String> sent=new java.util.concurrent.atomic.AtomicReference<>();worker=worker((chat,text)->{sent.set(text);accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",123L,0);});assertThat(worker.deliverOne()).isTrue();String html=sent.get();assertThat(html.length()).isLessThanOrEqualTo(4096);assertThat(html).contains(url.replace("&","&amp;"));int cards=html.split("View vacancy",-1).length-1;assertThat(cards).isBetween(1,9);assertThat(jdbc.queryForObject("select count(*) from notification_attempt_items",Integer.class)).isEqualTo(cards);assertThat(jdbc.queryForObject("select count(*) from notification_outbox where state='SENT'",Integer.class)).isEqualTo(cards);
  assertThatThrownBy(()->jdbc.update("update notification_attempt_items set generation=generation+1")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }

 @Test void spacingUsesCompletedSendTimeWhenPreparedAttemptWaitedBeforeDispatch()throws Exception{
  vacancy(true,"ELIGIBLE",95);vacancy(true,"ELIGIBLE",95);outbox.admitOne();var prepared=worker.prepareOne();assertThat(prepared).isNotNull();
  clock=Clock.offset(clock,Duration.ofSeconds(120));
  org.springframework.test.util.ReflectionTestUtils.setField(worker,"clock",clock);assertThat(worker.dispatch(prepared)).isTrue();
  assertThat(worker.deliverOne()).isFalse();assertThat(accepted).hasValue(1);
 }

 @Test void thousandInvitedCandidatesUseDurableFairDeliveryArbitration()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();jdbc.update("update notification_preferences set quiet_start='00:00',quiet_end='23:59' where candidate_id=?",candidate);
  List<Long> allowed=new ArrayList<>();allowed.add(9002L);long target=0;
  for(int n=0;n<999;n++){
   long user=9100L+n;allowed.add(user);String session=com.jobpilot.common.Hashing.sha256("synthetic-fairness-"+user+UUID.randomUUID());
   long id=jdbc.queryForObject("insert into candidates(stable_key,created_at,updated_at) values(?,current_timestamp,current_timestamp) returning id",Long.class,"fairness-"+UUID.randomUUID());target=id;
   jdbc.update("insert into telegram_identities values(?,?,current_timestamp)",user,id);jdbc.update("insert into auth_sessions values(?,?,?,?,?, ?,null)",session,user,id,identities.credentialScope(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plusSeconds(900)));
   jdbc.update("insert into search_profile_revisions select ?,revision,expected_revision,request_id,payload,completed,created_at from search_profile_revisions where candidate_id=? and revision=1",id,candidate);jdbc.update("insert into search_profile_state values(?,1)",id);
   jdbc.update("insert into notification_preferences(candidate_id,revision,generation,enabled,quiet_start,quiet_end,consent_session_hash,telegram_user_id,credential_scope,consent_policy,consent_at,updated_at) values(?,1,1,true,?,?,?, ?,?,'phase5-eligible-only-v1',?,?)",id,n==998?null:"00:00",n==998?null:"23:59",session,user,identities.credentialScope(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));
   jdbc.update("insert into notification_connections values(?,?,?,?,'CONNECTED',current_timestamp,null)",id,user,user,identities.credentialScope());
   long result=jdbc.queryForObject("insert into candidate_match_results(candidate_id,job_id,search_profile_version,job_revision,extractor_version,scorer_version,eligibility,compatibility_score,recommendation_score,evidence_coverage,payload,published_at,computed_at) select ?,job_id,search_profile_version,job_revision,extractor_version,scorer_version,eligibility,compatibility_score,recommendation_score,evidence_coverage,payload,published_at,computed_at from candidate_match_results where candidate_id=? and job_id=? returning id",Long.class,id,candidate,job);
   jdbc.update("insert into candidate_match_current values(?,?,?)",id,job,result);jdbc.update("insert into notification_outbox(candidate_id,job_id,result_id,generation,state,next_attempt_at,expires_at,created_at) values(?,?,?,1,'PENDING',?,?,?)",id,job,result,Timestamp.from(clock.instant()),Timestamp.from(clock.instant().plusSeconds(86400)),Timestamp.from(clock.instant()));
  }
  var policy=new com.jobpilot.identity.IdentityProperties(true,true,4242L,allowed,null,null);var configured=new NotificationSettingsService(jdbc,identities,policy,new NotificationProperties(true),new org.springframework.mock.env.MockEnvironment(),json,clock);
  long started=System.nanoTime();int ticks=0;
  for(;ticks<1000&&accepted.get()==0;ticks++){
   var sender=new NotificationDeliveryWorker(jdbc,manager,configured,gate,clock,catalogue,(chat,text)->{assertThat(chat).isEqualTo(10098);accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",123L,0);});sender.deliverOne();clock=Clock.offset(clock,Duration.ofSeconds(1));
  }
  assertThat(accepted).as("candidate1000 must get a turn despite999 quiet lower IDs").hasValue(1);assertThat(jdbc.queryForObject("select state from notification_outbox where candidate_id=? and job_id=?",String.class,target,job)).isEqualTo("SENT");System.out.println("TASK2_FAIRNESS invited=1000 quiet=999 recipientTicks="+ticks+" elapsedMs="+Duration.ofNanos(System.nanoTime()-started).toMillis());
 }
}
