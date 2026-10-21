package com.jobpilot.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.Hashing;
import com.jobpilot.jobs.domain.*;
import com.jobpilot.personalizedmatching.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Synthetic owned crash/history records shared by context and saved-image rehearsals. */
final class NotificationRecoveryFixture {
 static final Set<String> TABLES=Set.of("notification_preferences","notification_preference_revisions","notification_connections",
  "notification_activation_challenges","notification_discoveries","notification_outbox","notification_digest_envelopes",
  "notification_attempts","notification_attempt_outcomes","notification_runtime_state","notification_attempt_items");
 record Table(List<String> columns,List<List<String>> rows) { }
 record Snapshot(Map<String,Table> tables,Table deliverySequence) { }
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static Snapshot snapshot(Connection c)throws Exception {
  var names=new TreeSet<String>();
  try(var q=c.createStatement();var rs=q.executeQuery("select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name like 'notification_%'")){while(rs.next())names.add(rs.getString(1));}
  if(!names.equals(TABLES))throw new AssertionError("notification table inventory changed: "+names);
  var tables=new TreeMap<String,Table>();for(String name:names)tables.put(name,table(c,name));
  return new Snapshot(tables,table(c,"notification_delivery_checks","last_value,is_called"));
 }
 static Table table(Connection c,String name)throws Exception {return table(c,name,"*");}
 static Table table(Connection c,String name,String projection)throws Exception {
  var columns=new ArrayList<String>();var rows=new ArrayList<List<String>>();
  try(var q=c.createStatement();var rs=q.executeQuery("select "+projection+" from "+name)){
   for(int i=1;i<=rs.getMetaData().getColumnCount();i++)columns.add(rs.getMetaData().getColumnName(i));
   while(rs.next()){var row=new ArrayList<String>();for(int i=1;i<=columns.size();i++)row.add(rs.getString(i));rows.add(row);}
  }rows.sort(Comparator.comparing(Object::toString));return new Table(columns,rows);
 }
 static void sql(Connection c,String text,Object... values)throws Exception {try(var q=c.prepareStatement(text)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);q.executeUpdate();}}
 static long scalar(Connection c,String text,Object... values)throws Exception {try(var q=c.prepareStatement(text)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);try(var rs=q.executeQuery()){if(!rs.next())throw new AssertionError(text);return rs.getLong(1);}}}
 static long candidate(Connection c,long telegram)throws Exception{return scalar(c,"select candidate_id from telegram_identities where telegram_user_id=?",telegram);}
 static void seed(Connection c,long candidate)throws Exception {
  c.setAutoCommit(false);
  try {
   scalar(c,"select id from candidates where id=? for update",candidate);
   long revision=scalar(c,"select active_revision from search_profile_state where candidate_id=?",candidate);
   long generation=scalar(c,"select generation from notification_preferences where candidate_id=? and enabled and not paused and immediate",candidate);
   sql(c,"insert into notification_connections(candidate_id,telegram_user_id,chat_id,credential_scope,status,activated_at) select candidate_id,telegram_user_id,telegram_user_id,credential_scope,'CONNECTED',current_timestamp from notification_preferences where candidate_id=? on conflict(candidate_id) do nothing",candidate);
   long cursor=scalar(c,"select change_revision from matching_catalogue_state where id=1 for update");
   for(String state:List.of("PENDING","RETRY","SENDING","SENT","UNCERTAIN","CANCELED","DROPPED","DIGEST")) {
    Instant now=Instant.now();String external="recovery-"+UUID.randomUUID();
    long job=scalar(c,"insert into jobs(source,external_id,canonical_url,title,company,location,description,fetched_at,first_seen_at,last_seen_at,status,raw_payload_hash,description_hash,normalized_fingerprint,remote_type,employment_type) values('recovery',?,'https://example.org/'||?,'Recovery Engineer','Synthetic','Romania','No prior experience required.',current_timestamp,current_timestamp,current_timestamp,'NEW',repeat('a',64),repeat('b',64),repeat('c',64),'REMOTE','FULL_TIME') returning id",external,external);
    var source=new Job("recovery",external,"https://example.org/"+external,"Recovery Engineer","Synthetic","Romania",RemoteType.REMOTE,"FULL_TIME","No prior experience required.",now,null,"a".repeat(64),"b".repeat(64),"c".repeat(64),now);
    String facts=JSON.writeValueAsString(new VacancyFactExtractor().extract(source,null));
    sql(c,"insert into vacancy_fact_revisions values(?,1,?,?,null,?,current_timestamp)",job,VacancyFactExtractor.VERSION,Hashing.sha256(facts+"\0"),facts);
    sql(c,"insert into vacancy_fact_state values(?,1,?)",job,VacancyFactExtractor.VERSION);
    sql(c,"insert into matching_catalogue_changes values(?,?,1,?)",++cursor,job,VacancyFactExtractor.VERSION);
    sql(c,"insert into notification_discoveries values(?,?,current_timestamp)",job,cursor);
    long result=scalar(c,"insert into candidate_match_results(candidate_id,job_id,search_profile_version,job_revision,extractor_version,scorer_version,eligibility,compatibility_score,recommendation_score,evidence_coverage,payload,published_at,computed_at) values(?,?,?,1,?,?,'ELIGIBLE',95,95,90,'{}',current_timestamp,current_timestamp) returning id",candidate,job,revision,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION);
    sql(c,"insert into candidate_match_current values(?,?,?)",candidate,job,result);
    Long attempt=null;String mode=null;boolean digest=state.equals("DIGEST");String logical=digest?"SENT":state;
    if(List.of("RETRY","SENDING","SENT","UNCERTAIN","DIGEST").contains(state)) {
     mode=digest?"DIGEST":"IMMEDIATE";Long envelope=digest?scalar(c,"insert into notification_digest_envelopes(candidate_id,local_date,created_at) values(?,current_date-2,current_timestamp-interval '2 days') returning id",candidate):null;
     attempt=scalar(c,"insert into notification_attempts(candidate_id,mode,envelope_id,fence,local_date,timezone,payload,created_at,process_nonce) values(?,?,?,1,current_date-2,'UTC','Synthetic immutable recovery envelope',current_timestamp-interval '2 days',repeat('e',64)) returning id",candidate,mode,envelope);
     sql(c,"insert into notification_attempt_items values(?,?,?,?,?,1)",attempt,candidate,job,result,generation);
     sql(c,"insert into notification_attempt_outcomes(attempt_id,state,category,telegram_message_id,completed_at) values(?,?,?, ?,case when ?='SENDING' then null else current_timestamp-interval '2 days' end)",attempt,logical,"SYNTHETIC_"+logical,logical.equals("SENT")?123L:null,logical);
    }
    sql(c,"insert into notification_outbox(candidate_id,job_id,result_id,generation,state,mode,fence,attempts,next_attempt_at,expires_at,created_at,attempt_id,reason,sent_at) values(?,?,?,?,?,?,?, ?,current_timestamp-interval '1 minute',current_timestamp+interval '23 hours',current_timestamp,?,?,case when ?='SENT' then current_timestamp-interval '2 days' else null end)",candidate,job,result,generation,logical,mode,attempt==null?0:1,attempt==null?0:1,attempt,attempt==null?null:"SYNTHETIC_"+logical,logical);
   }
   sql(c,"update matching_catalogue_state set change_revision=? where id=1",cursor);
   sql(c,"update candidate_matching_state set phase='READY',event_cursor=? where candidate_id=?",cursor,candidate);
   sql(c,"update notification_preferences set event_cursor=?,last_scanned_at=current_timestamp-interval '3 days',last_delivery_check=nextval('notification_delivery_checks') where candidate_id=?",cursor,candidate);
   c.commit();
  }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
  assertDeliverablePending(c,candidate);
 }
 static void assertDeliverablePending(Connection c,long candidate)throws Exception {
  long count=scalar(c,"select count(*) from notification_outbox o join notification_preferences p on p.candidate_id=o.candidate_id join notification_connections n on n.candidate_id=p.candidate_id and n.status='CONNECTED' and n.credential_scope=p.credential_scope join auth_sessions a on a.candidate_id=p.candidate_id and a.token_hash=p.consent_session_hash and a.revoked_at is null join candidate_match_current m on m.candidate_id=o.candidate_id and m.job_id=o.job_id and m.result_id=o.result_id join candidate_match_results r on r.id=m.result_id and r.candidate_id=o.candidate_id and r.eligibility='ELIGIBLE' and r.compatibility_score>=p.minimum_score join search_profile_state s on s.candidate_id=r.candidate_id and s.active_revision=r.search_profile_version join search_profile_revisions pr on pr.candidate_id=s.candidate_id and pr.revision=s.active_revision and pr.completed join vacancy_fact_state f on f.job_id=o.job_id and f.job_revision=r.job_revision and f.extractor_version=r.extractor_version join jobs j on j.id=o.job_id and j.status<>'EXPIRED' where o.candidate_id=? and p.enabled and not p.paused and p.immediate and p.revoked_at is null and o.generation=p.generation and o.state='PENDING' and o.expires_at>current_timestamp and o.next_attempt_at<=current_timestamp and (j.deadline is null or j.deadline>current_timestamp) and o.job_id in (select job_id from notification_discoveries where discovery_revision>p.baseline_revision and discovered_at>current_timestamp-interval '24 hours')",candidate);
  if(count<1)throw new AssertionError("No presently deliverable pending owned notification");
  if(scalar(c,"select count(*) from notification_preferences where candidate_id=? and quiet_start is null and quiet_end is null and telegram_user_id=9002 and eligibility_policy='ELIGIBLE_ONLY'",candidate)!=1)throw new AssertionError("Pending fixture must have unrestricted immediate schedule/private beta identity");
  if(scalar(c,"select count(*) from notification_attempts a join notification_attempt_outcomes o on o.attempt_id=a.id join notification_preferences p on p.candidate_id=a.candidate_id where a.candidate_id=? and o.state not in ('RETRY','CANCELED') and (a.local_date=(current_timestamp at time zone p.timezone)::date or greatest(a.created_at,coalesce(o.completed_at,a.created_at))>current_timestamp-interval '24 hours')",candidate)!=0)throw new AssertionError("Historical fixture consumed current local-day or rolling delivery budget");
  if(scalar(c,"select count(*) from notification_outbox o join candidate_match_results r on r.id=o.result_id where o.candidate_id=? and o.state='PENDING' and r.extractor_version=? and r.scorer_version=?",candidate,VacancyFactExtractor.VERSION,PersonalizedMatcher.VERSION)<1)throw new AssertionError("Pending fixture semantic versions are stale");
 }
}
