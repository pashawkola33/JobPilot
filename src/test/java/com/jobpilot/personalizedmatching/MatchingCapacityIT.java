package com.jobpilot.personalizedmatching;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.jobs.domain.*;
import com.jobpilot.jobs.repository.*;
import com.jobpilot.jobs.service.JobProcessor;
import com.jobpilot.searchprofile.SearchProfile;
import java.time.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Finite synthetic benchmark. Never connects to an external database or source. */
@Testcontainers
@SpringBootTest(properties={"jobpilot.scheduled-tasks-enabled=false"})
class MatchingCapacityIT {
 @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine")
  .withCreateContainerCmdModifier(c->c.getHostConfig().withMemory(512L*1024*1024).withNanoCPUs(1_000_000_000L));
 @DynamicPropertySource static void database(DynamicPropertyRegistry p){p.add("spring.datasource.url",DB::getJdbcUrl);p.add("spring.datasource.username",DB::getUsername);p.add("spring.datasource.password",DB::getPassword);}
 @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired JobProcessor processor;@Autowired JobRepository jobs;
 @Autowired MatchingCatalogue catalogue;@Autowired MatchingWorkService work;@Autowired PlatformTransactionManager manager;
 final Random random=new Random(4242);
 final String[] titles={"Java developer intern","Marketing assistant","Data analyst","Logistics coordinator","Finance assistant"};
 final String[] skills={"Java, SQL","SEO, Google Analytics","Python, Excel","SAP, inventory management","Excel, accounting"};
 final String[] catalogueTables={"jobs","job_requirements","job_scores","vacancy_fact_revisions","vacancy_fact_state","matching_catalogue_changes","matching_catalogue_state"};
 final String[] matchTables={"candidate_match_results","candidate_match_current","candidate_matching_state"};
 long nanos(){return System.nanoTime();}
 double seconds(long start){return (nanos()-start)/1e9;}
 long count(String table){return jdbc.queryForObject("select count(*) from "+table,Long.class);}
 Map<String,Long> storage(String[] tables){Map<String,Long> out=new LinkedHashMap<>();for(String table:tables)out.put(table,jdbc.queryForObject("select pg_total_relation_size(?)",Long.class,table));return out;}
 String noise(){StringBuilder s=new StringBuilder();for(int i=0;i<5000;i++)s.append((char)('a'+random.nextInt(26)));return s.toString();}
 RawJob vacancy(int id,int category){String country=category==3?"Germany":"Romania",language=category==3?"German":"English";
  String body="Required skills: "+skills[category]+". No prior experience required. "+language+" B2 required. Work authorization in "+country+" required. "+(category==3?"Remote in Germany only.":"Remote worldwide.")+" "+noise();
  return new RawJob("capacity-fixture","job-"+id,"https://example.org/capacity/"+id,titles[category],"Synthetic capacity fixture",category==3?"Germany":"Bucharest, Romania",body,"FULL_TIME",Instant.now(),null,"synthetic public vacancy");
 }
 ObjectNode profile(int category){ObjectNode p=json.createObjectNode();p.putArray("desiredTitles").add(titles[category]);
  var list=p.putArray("technicalSkills");for(String value:skills[category].split(", "))list.add(value);
  p.put("experienceYears",category==0||category==1?0:6);p.put("currentCountry",category==3?"Germany":"Romania");p.put("currentCity",category==3?"Berlin":"Bucharest");p.put("relocation","NO");
  p.putArray("targetRegions").add(category==3?"Germany":"Romania");p.putArray("workFormats").add(category==3?"ONSITE":"REMOTE");p.putArray("employmentTypes").add("FULL_TIME");
  p.putArray("languages").addObject().put("language",category==3?"German":"English").put("proficiency","C1");
  if(category==3)p.withArray("languages").addObject().put("language","English").put("proficiency","A2");
  p.putArray("authorizationByCountry").addObject().put("country",category==3?"DE":"RO").put("status","AUTHORIZED");return p;
 }
 long candidate(int index,ObjectNode profile) {
  return new TransactionTemplate(manager).execute(tx->{long id=jdbc.queryForObject("insert into candidates(stable_key,created_at,updated_at) values(?,current_timestamp,current_timestamp) returning id",Long.class,"capacity-candidate-"+index);
   jdbc.update("insert into search_profile_revisions(candidate_id,revision,expected_revision,request_id,payload,completed,created_at) values(?,1,0,'capacity-profile',?,true,current_timestamp)",id,profile.toString());jdbc.update("insert into search_profile_state values(?,1)",id);MatchingWorkService.activate(jdbc,id,1,true);return id;});
 }
 String constant(String name)throws Exception{var field=PersonalizedMatchQuery.class.getDeclaredField(name);field.setAccessible(true);return (String)field.get(null);}
 @Test void recordsCatalogueGrowthAndFiftyCandidateCostWithinFiniteBatches() throws Exception {
  assertThat(count("jobs")).isZero();Map<String,Object> report=new LinkedHashMap<>();long start=nanos();
  for(int i=0;i<100;i++)assertThat(processor.process(vacancy(i,0)).accepted()).isTrue();
  report.put("legacy_admitted_ingestion_seconds",seconds(start));var before=storage(catalogueTables);report.put("catalogue_bytes_before_retaining_rejected",before);
  var ownerScores=jdbc.queryForList("select job_id,score,band,strengths,risks,hard_blockers from job_scores order by job_id");
  start=nanos();long marketing=0;
  for(int i=100;i<200;i++){var result=processor.process(vacancy(i,1+(i-100)%4));assertThat(result.persisted()).isTrue();assertThat(result.accepted()).isFalse();assertThat(result.score()).isNull();if(i==100)marketing=result.job().getId();}
  report.put("catalogue_only_ingestion_seconds",seconds(start));report.put("catalogue_bytes_after_retaining_rejected",storage(catalogueTables));
  assertThat(count("jobs")).isEqualTo(200);assertThat(count("job_scores")).isEqualTo(100);
  assertThat(jdbc.queryForObject("select count(*) from jobs where legacy_admission_pending",Long.class)).isEqualTo(100);
  List<Long> candidates=new ArrayList<>();for(int i=0;i<50;i++)candidates.add(candidate(i,profile(i%5)));
  String walBefore=jdbc.queryForObject("select pg_current_wal_lsn()::text",String.class);
  var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();long cpuBefore=os.getProcessCpuTime();
  List<Long> batchNanos=new ArrayList<>();long heapPeak=0;
  long heapBefore=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();start=nanos();int batches=0;
  while(true){long batchStart=nanos();boolean processed=work.runOne();if(!processed)break;batchNanos.add(nanos()-batchStart);heapPeak=Math.max(heapPeak,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());if(++batches>500)throw new AssertionError("unbounded backfill");}
  double backfillSeconds=seconds(start);
  assertThat(batches).isEqualTo(400);assertThat(count("candidate_match_current")).isEqualTo(10000);assertThat(count("candidate_match_results")).isEqualTo(10000);
  report.put("candidates",50);report.put("vacancies",200);report.put("previously_rejected_retained",100);report.put("backfill_batches",batches);report.put("batch_ceiling",25);report.put("backfill_seconds",backfillSeconds);report.put("evaluations_per_second_including_postgresql",10000/backfillSeconds);
  batchNanos.sort(Long::compare);report.put("batch_latency_p50_ms",batchNanos.get(batchNanos.size()/2)/1e6);report.put("batch_latency_p95_ms",batchNanos.get((int)Math.ceil(batchNanos.size()*0.95)-1)/1e6);report.put("process_cpu_seconds_backfill",(os.getProcessCpuTime()-cpuBefore)/1e9);report.put("sampled_peak_heap_bytes",heapPeak);report.put("backfill_wal_bytes",jdbc.queryForObject("select pg_wal_lsn_diff(pg_current_wal_lsn(),?::pg_lsn)::bigint",Long.class,walBefore));
  report.put("matching_physical_bytes",storage(matchTables));report.put("retained_vacancy_result_rows",jdbc.queryForObject("select count(*) from candidate_match_results r join jobs j on j.id=r.job_id where j.legacy_admission_pending",Long.class));
  report.put("retained_result_payload_bytes",jdbc.queryForObject("select sum(octet_length(r.payload)) from candidate_match_results r join jobs j on j.id=r.job_id where j.legacy_admission_pending",Long.class));
  report.put("heap_used_before_bytes",heapBefore);report.put("heap_used_after_bytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());report.put("jvm_heap_limit_bytes",Runtime.getRuntime().maxMemory());report.put("jvm_visible_cpus",Runtime.getRuntime().availableProcessors());report.put("postgres_memory_limit_bytes",512L*1024*1024);report.put("postgres_cpu_limit",1);
  var examples=new LinkedHashMap<String,Object>();for(int i=0;i<4;i++)examples.put(List.of("A Java junior","B Marketing graduate","C Experienced data analyst","D Logistics Germany").get(i),jdbc.queryForMap("select eligibility,compatibility_score,recommendation_score,evidence_coverage from candidate_match_results where candidate_id=? and job_id=?",candidates.get(i),marketing));report.put("same_marketing_vacancy_examples",examples);
  assertThat(((Map<?,?>)examples.get("B Marketing graduate")).get("eligibility")).isEqualTo("ELIGIBLE");assertThat(((Map<?,?>)examples.get("D Logistics Germany")).get("eligibility")).isEqualTo("INELIGIBLE");
  long edited=candidates.getFirst();start=nanos();new TransactionTemplate(manager).executeWithoutResult(tx->{jdbc.queryForObject("select id from candidates where id=? for update",Long.class,edited);jdbc.update("insert into search_profile_revisions(candidate_id,revision,expected_revision,request_id,payload,completed,created_at) values(?,2,1,'capacity-edit',?,true,current_timestamp)",edited,profile(0).toString());jdbc.update("update search_profile_state set active_revision=2 where candidate_id=?",edited);MatchingWorkService.activate(jdbc,edited,2,true);});
  int editedBatches=0;while(work.runOne()){if(++editedBatches>10)throw new AssertionError("unbounded one-candidate rescan");}
  report.put("one_profile_change_seconds",seconds(start));report.put("one_profile_change_batches",editedBatches);assertThat(editedBatches).isEqualTo(8);assertThat(count("candidate_match_results")).isEqualTo(10200);assertThat(count("candidate_match_current")).isEqualTo(10000);
  long changed=jdbc.queryForObject("select id from jobs where not legacy_admission_pending order by id limit 1",Long.class);start=nanos();
  new TransactionTemplate(manager).executeWithoutResult(tx->{Job j=jobs.findById(changed).orElseThrow();RawJob raw=new RawJob(j.getSource(),j.getExternalId(),j.getCanonicalUrl(),j.getTitle(),j.getCompany(),j.getLocation(),j.getDescription()+" Preferred skills: Docker.",j.getEmploymentType(),j.getPublishedAt(),j.getDeadline(),"neutral fact update");catalogue.capture(j,raw);});
  int updates=0;while(work.runOne()){if(++updates>60)throw new AssertionError("unbounded vacancy fanout");}
  report.put("one_vacancy_change_seconds",seconds(start));report.put("one_vacancy_change_candidate_batches",updates);assertThat(updates).isEqualTo(50);assertThat(count("candidate_match_results")).isEqualTo(10250);
  assertThat(jdbc.queryForList("select job_id,score,band,strengths,risks,hard_blockers from job_scores order by job_id")).isEqualTo(ownerScores);assertThat(count("telegram_job_delivery")).isZero();
  jdbc.execute("analyze");var params=new MapSqlParameterSource("owner",candidates.getFirst()).addValue("extractor",VacancyFactExtractor.VERSION).addValue("scorer",PersonalizedMatcher.VERSION).addValue("now",java.sql.Timestamp.from(Instant.now()));
  String sql="select "+constant("COLUMNS")+constant("FROM")+" where "+constant("CURRENT")+" and r.eligibility<>'INELIGIBLE' order by "+constant("GROUP")+",r.compatibility_score desc,r.published_at desc,r.job_id desc limit 26";
  String plan=new NamedParameterJdbcTemplate(jdbc).queryForObject("explain(analyze,buffers,format json) "+sql,params,String.class);report.put("production_listing_query_plan",json.readTree(plan));
  assertThat(plan).contains("candidate_match_listing_idx");
  Files.writeString(Path.of("target/matching-capacity.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
  System.out.println("CAPACITY: 50 candidates x 200 jobs; 100 catalogue-only; "+Math.round(10000/backfillSeconds)+" persisted evaluations/sec; full metrics target/matching-capacity.json");
 }
}
