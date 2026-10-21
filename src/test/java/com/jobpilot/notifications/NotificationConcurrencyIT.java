package com.jobpilot.notifications;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;import java.sql.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
class NotificationConcurrencyIT extends NotificationTestSupport {
 @Test void expiredClaimCanBeReclaimedButOldFenceCannotPrepare()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();var old=worker.claimOne();assertThat(old).isNotNull();
  assertThat(worker.claimOne()).isNull();jdbc.update("update notification_outbox set lease_until=? where candidate_id=?",Timestamp.from(clock.instant().minusSeconds(1)),candidate);
  var fresh=worker.claimOne();assertThat(fresh).isNotNull();assertThat(fresh.fence()).isGreaterThan(old.fence());assertThat(worker.prepare(old)).isNull();var prepared=worker.prepare(fresh);assertThat(prepared).isNotNull();assertThat(worker.dispatch(prepared)).isTrue();assertThat(worker.dispatch(prepared)).isFalse();assertThat(state(job)).isEqualTo("SENT");assertThat(accepted).hasValue(1);
 }
 @Test void committedSendingSurvivesCrashAsHeldAndReplacementCannotDispatchHandle()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();var prepared=worker.prepareOne();assertThat(prepared).isNotNull();
  var replacement=worker((chat,body)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",1L,0);});
  assertThat(replacement.dispatch(prepared)).isFalse();assertThat(replacement.deliverOne()).isFalse();assertThat(state(job)).isEqualTo("SENDING");assertThat(accepted).hasValue(0);
  gate=new NotificationRuntimeGate(jdbc,new NotificationProperties(true),new org.springframework.mock.env.MockEnvironment().withProperty("jobpilot.notifications.approval-file",approval.toString()),lifecycle);
  assertThat(gate.permitted()).isFalse();arm();replacement=worker((chat,body)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",2L,0);});assertThat(replacement.deliverOne()).isFalse();assertThat(accepted).hasValue(0);
 }
 @Test void consentAndProfileWinningCandidateLockPreventPreparedSend()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();var prepared=worker.prepareOne();assertThat(prepared).isNotNull();
  settings.save(principal,new NotificationSettingsService.Update(1L,UUID.randomUUID().toString(),false,false,true,false,85,"UTC",null,null));
  worker.dispatch(prepared);assertThat(state(job)).isEqualTo("CANCELED");assertThat(accepted).hasValue(0);
 }
 @Test void sendHoldsCandidateUntilOutcomeAndMutationWaitsAtRealPostgresBarrier()throws Exception{
  vacancy(true,"ELIGIBLE",95);outbox.admitOne();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);AtomicInteger dispatchPid=new AtomicInteger();
  var sender=worker((chat,body)->{dispatchPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));accepted.incrementAndGet();entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("fixture barrier expired");}catch(InterruptedException e){throw new IllegalStateException(e);}return new NotificationTransport.Outcome("SENT","ACCEPTED",123L,0);});
  try(var pool=Executors.newFixedThreadPool(2)){
   var send=pool.submit(sender::deliverOne);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
   var mutation=pool.submit(()->settings.save(principal,new NotificationSettingsService.Update(1L,UUID.randomUUID().toString(),false,false,true,false,85,"UTC",null,null)));
   long deadline=System.nanoTime()+Duration.ofMillis(700).toNanos();boolean blocked=false;
   while(System.nanoTime()<deadline){blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,dispatchPid.get()));if(blocked)break;}
   assertThat(blocked).as("candidate mutation blocks behind the actual dispatch transaction").isTrue();release.countDown();assertThat(send.get(3,TimeUnit.SECONDS)).isTrue();assertThat(mutation.get(3,TimeUnit.SECONDS).preferences().enabled()).isFalse();assertThat(accepted).hasValue(1);
  }finally{release.countDown();}
 }
 @Test void heldJobLockRefusesWithinBoundAndRecordsNoAttempt()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();
  try(var held=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())){held.setAutoCommit(false);try(var q=held.prepareStatement("select id from jobs where id=? for update")){q.setLong(1,job);q.executeQuery().close();}
   long start=System.nanoTime();assertThat(worker.deliverOne()).isFalse();long elapsed=Duration.ofNanos(System.nanoTime()-start).toMillis();assertThat(elapsed).isBetween(700L,3500L);assertThat(jdbc.queryForObject("select count(*) from notification_attempts",Long.class)).isZero();held.rollback();
  }assertThat(accepted).hasValue(0);
 }
 @Test void outcomeWriteFailureAfterAcceptanceNeverReclaimsOrResends()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();
  jdbc.execute("create function fixture_reject_outcome() returns trigger language plpgsql as $$ begin raise exception 'synthetic outcome persistence failure'; end $$");jdbc.execute("create trigger fixture_outcome_failure before update on notification_attempt_outcomes for each row execute function fixture_reject_outcome()");
  try{assertThat(worker.deliverOne()).isTrue();assertThat(state(job)).isEqualTo("SENDING");assertThat(worker.deliverOne()).isFalse();assertThat(accepted).hasValue(1);}finally{jdbc.execute("drop trigger fixture_outcome_failure on notification_attempt_outcomes");jdbc.execute("drop function fixture_reject_outcome()");}
 }
 @Test void twoImmediateDigestClaimersAndRepeatedStressHaveOneIdentityAndNoDeadlocks()throws Exception{
  jdbc.update("update notification_preferences set digest=true");for(int n=0;n<40;n++)vacancy(true,"ELIGIBLE",95);outbox.admitOne();
  long deadlocks=jdbc.queryForObject("select deadlocks from pg_stat_database where datname=current_database()",Long.class);long start=System.nanoTime();var selectionTimes=new ConcurrentLinkedQueue<Long>();
  var sqlLogger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(NotificationSqlFailure.class);var sqlEvents=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();sqlEvents.start();sqlLogger.addAppender(sqlEvents);
  try(var pool=Executors.newFixedThreadPool(8)){var barrier=new CyclicBarrier(8);var futures=new ArrayList<Future<?>>();for(int n=0;n<8;n++)futures.add(pool.submit(()->{barrier.await();for(int i=0;i<20;i++){outbox.admitOne();long tick=System.nanoTime();worker.deliverOne();selectionTimes.add(Duration.ofNanos(System.nanoTime()-tick).toMillis());}return null;}));for(var task:futures)task.get(30,TimeUnit.SECONDS);}
  assertThat(jdbc.queryForObject("select deadlocks from pg_stat_database where datname=current_database()",Long.class)).isEqualTo(deadlocks);assertThat(jdbc.queryForObject("select count(*) from notification_outbox",Long.class)).isEqualTo(40);assertThat(jdbc.queryForObject("select count(*) from notification_outbox where state='SENT'",Long.class)).isBetween(1L,11L);assertThat(accepted.get()).isBetween(1,2);assertThat(Duration.ofNanos(System.nanoTime()-start).toSeconds()).isLessThan(30);
  sqlLogger.detachAppender(sqlEvents);assertThat(sqlEvents.list).noneMatch(event->event.getFormattedMessage().contains("40P01"));List<Long> durations=new ArrayList<>(selectionTimes);Collections.sort(durations);
  System.out.println("TASK2_STRESS deadlocks=0 SQLState40P01=0 workers=8 loops=20 elapsedMs="+Duration.ofNanos(System.nanoTime()-start).toMillis()+" accepted="+accepted.get()+" selectionSamples="+durations.size()+" selectionP50ms="+durations.get(durations.size()/2)+" selectionP95ms="+durations.get((int)(durations.size()*.95)-1));
 }
 @Test void wrongEpochUnsafeSymlinkAndRecoveryNeverArm()throws Exception{
  Files.writeString(approval,"0".repeat(64)+"\n"+gate.operatorIdentity().nonce()+"\n");assertThat(gate.permitted()).isFalse();arm();Files.setPosixFilePermissions(approval,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));assertThat(gate.permitted()).isFalse();arm();Path target=approval.resolveSibling("target");Files.move(approval,target);Files.createSymbolicLink(approval,target);assertThat(gate.permitted()).isFalse();Files.delete(approval);Files.move(target,approval);assertThat(gate.permitted()).isTrue();
  var recovery=new org.springframework.mock.env.MockEnvironment().withProperty("jobpilot.notifications.approval-file",approval.toString());recovery.setActiveProfiles("recovery");var disarmed=new NotificationRuntimeGate(jdbc,new NotificationProperties(true),recovery,lifecycle);assertThat(disarmed.permitted()).isFalse();
 }

 @Test void sessionPollingLeaseHasOneOwnerAndReleasesOnClose()throws Exception{
  var env=new org.springframework.mock.env.MockEnvironment().withProperty("jobpilot.telegram.bot-token","synthetic-poller-token");
  var first=new com.jobpilot.telegram.polling.TelegramPollingLease(jdbc,env);var second=new com.jobpilot.telegram.polling.TelegramPollingLease(jdbc,env);
  try(var lease=first.acquire()){assertThat(lease).isNotNull();assertThat(second.acquire()).isNull();}
  try(var lease=second.acquire()){assertThat(lease).isNotNull();}
 }
 @Test void bootstrapDoesNotLockNextJobBehindCataloguePublicationBarrier()throws Exception{
  List<Long> ids=jdbc.queryForList("select id from jobs where id not in(select job_id from vacancy_fact_state) order by id limit 2",Long.class);
  if(ids.size()<2){vacancy(false,"ELIGIBLE",95);vacancy(false,"ELIGIBLE",95);jdbc.execute("truncate vacancy_fact_state,vacancy_fact_revisions,matching_catalogue_changes cascade");ids=jdbc.queryForList("select id from jobs order by id limit 2",Long.class);}
  long second=ids.get(1);
  try(var held=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var probe=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());var pool=Executors.newSingleThreadExecutor()){
   held.setAutoCommit(false);int blocker;try(var q=held.createStatement();var r=q.executeQuery("select pg_backend_pid()")){r.next();blocker=r.getInt(1);}try(var q=held.createStatement()){q.executeQuery("select change_revision from matching_catalogue_state for update").close();}
   var bootstrap=pool.submit(catalogue::bootstrap);boolean blocked=false;long deadline=System.nanoTime()+Duration.ofMillis(700).toNanos();
   while(System.nanoTime()<deadline){try(var q=probe.prepareStatement("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))")){q.setInt(1,blocker);try(var r=q.executeQuery()){r.next();blocked=r.getBoolean(1);}}if(blocked)break;}
   assertThat(blocked).isTrue();probe.setAutoCommit(false);try(var q=probe.prepareStatement("select id from jobs where id=? for update nowait")){q.setLong(1,second);try(var r=q.executeQuery()){assertThat(r.next()).isTrue();}}probe.rollback();held.rollback();assertThat(bootstrap.get(10,TimeUnit.SECONDS)).isPositive();
  }
 }

 @Test void profileWinningBeforeDispatchCancelsPreparedRecommendation()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();var prepared=worker.prepareOne();
  var profile=json.readValue("{\"desiredTitles\":[\"Analyst\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Romania\"]}",com.jobpilot.searchprofile.SearchProfile.class);
  profiles.save(principal,new com.jobpilot.searchprofile.SearchProfileService.Update(1L,UUID.randomUUID().toString(),profile));
  assertThat(worker.dispatch(prepared)).isTrue();assertThat(state(job)).isEqualTo("CANCELED");assertThat(accepted).hasValue(0);
 }
 @Test void factChangeAfterPreparationCannotSendStaleRecommendation()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();var prepared=worker.prepareOne();
  tx(()->{var j=jobs.findById(job).orElseThrow();catalogue.capture(j,new com.jobpilot.jobs.domain.RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),"Required skills: Python. Remote worldwide. Updated requirements.",j.getEmploymentType(),clock.instant(),null,"changed"));return null;});
  worker.dispatch(prepared);assertThat(state(job)).isEqualTo("CANCELED");assertThat(accepted).hasValue(0);
 }
 @Test void directRecoveryServicesLeaveAllNotificationRowsUntouched()throws Exception{
  long job=vacancy(true,"ELIGIBLE",95);outbox.admitOne();var before=jdbc.queryForList("select * from notification_outbox order by job_id");
  var recovery=new org.springframework.mock.env.MockEnvironment().withProperty("jobpilot.notifications.approval-file",approval.toString());recovery.setActiveProfiles("recovery");var disarmed=new NotificationRuntimeGate(jdbc,new NotificationProperties(true),recovery,lifecycle);
  var producer=new NotificationOutboxService(jdbc,manager,settings,disarmed,clock,catalogue);var sender=new NotificationDeliveryWorker(jdbc,manager,settings,disarmed,clock,catalogue,(chat,text)->{accepted.incrementAndGet();return new NotificationTransport.Outcome("SENT","ACCEPTED",1L,0);});assertThat(producer.admitOne()).isFalse();assertThat(sender.deliverOne()).isFalse();assertThat(sender.claimOne()).isNull();assertThat(jdbc.queryForList("select * from notification_outbox order by job_id")).isEqualTo(before);assertThat(accepted).hasValue(0);
 }

 @Test void profileSaveWaitsBehindInFlightSendAtRealPostgresCandidateBarrier()throws Exception{
  vacancy(true,"ELIGIBLE",95);outbox.admitOne();var profile=json.readValue("{\"desiredTitles\":[\"Analyst\"],\"technicalSkills\":[\"SQL\"],\"workFormats\":[\"REMOTE\"],\"targetRegions\":[\"Romania\"]}",com.jobpilot.searchprofile.SearchProfile.class);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);AtomicInteger dispatchPid=new AtomicInteger();
  var sender=worker((chat,body)->{dispatchPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));accepted.incrementAndGet();entered.countDown();try{if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("fixture barrier expired");}catch(InterruptedException e){throw new IllegalStateException(e);}return new NotificationTransport.Outcome("SENT","ACCEPTED",123L,0);});
  try(var pool=Executors.newFixedThreadPool(2)){
   var send=pool.submit(sender::deliverOne);assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();var mutation=pool.submit(()->profiles.save(principal,new com.jobpilot.searchprofile.SearchProfileService.Update(1L,UUID.randomUUID().toString(),profile)));
   long deadline=System.nanoTime()+Duration.ofMillis(700).toNanos();boolean blocked=false;while(System.nanoTime()<deadline){blocked=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where ?=any(pg_blocking_pids(pid)))",Boolean.class,dispatchPid.get()));if(blocked)break;}
   assertThat(blocked).as("actual SearchProfileService.save waits on dispatch candidate lock").isTrue();release.countDown();assertThat(send.get(3,TimeUnit.SECONDS)).isTrue();mutation.get(3,TimeUnit.SECONDS);assertThat(jdbc.queryForObject("select active_revision from search_profile_state where candidate_id=?",Long.class,candidate)).isEqualTo(2);assertThat(accepted).hasValue(1);
  }finally{release.countDown();}
 }
}
