package com.jobpilot.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.candidate.CandidateProfileTestData;
import com.jobpilot.common.Hashing;
import java.sql.Connection;

/** Synthetic V13 records shared by the migration rehearsal; no external services. */
final class LegacyMigrationFixture {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static void execute(Connection c, String sql) throws Exception {
    try (var s=c.createStatement()) { s.execute(sql); }
  }
  static void seed(Connection c) throws Exception {
    for(int v=1;v<=2;v++){
      String hash=Hashing.sha256(JSON.writeValueAsString(CandidateProfileTestData.validProfile(v)));
      execute(c,"insert into candidate_profiles(id,profile_version,full_name,location,education_institution,degree,study_start_year,current_student,final_year_student,commercial_java_experience_years,source_hash,created_at,updated_at,active,active_slot) values ("+v+","+v+",'Test Candidate','Bucharest, Romania','Test University','BSc in Informatics',2025,true,false,0,'"+hash+"','2026-01-01Z','2026-01-01Z',"+(v==2)+","+(v==2?"1":"null")+")");
    }
    execute(c,"insert into candidate_skills(candidate_profile_id,stable_key,normalized_name,display_name,category,evidence_text,active,display_order) values(2,'java','java','java','JAVA','Verified test evidence',true,0)");
    execute(c,"insert into candidate_languages(candidate_profile_id,stable_key,language,verified_level,allowed_in_cv,active,display_order) values(2,'english','English','FLUENT',true,true,0)");
    execute(c,"insert into candidate_projects(id,candidate_profile_id,stable_key,name,description,project_type,technologies,active,display_order) values(10,2,'sample-project','Sample Project','Verified personal project','PERSONAL_PROJECT','Java|Spring Boot',true,0)");
    execute(c,"insert into candidate_project_bullets(project_id,stable_key,verified_text,keywords,active,display_order) values(10,'verified-api','Implements a verified REST API.','Java',true,0)");
    for(int id=101;id<=103;id++){
      execute(c,"insert into jobs(id,source,external_id,canonical_url,title,company,location,description,fetched_at,first_seen_at,last_seen_at,status,raw_payload_hash,description_hash,normalized_fingerprint,screening_disposition,location_disposition,career_disposition,relevance_disposition) values("+id+",'fixture','"+id+"','https://example.test/jobs/"+id+"','Synthetic Java Intern','Synthetic Company','Bucharest','Java internship with mentoring',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'NEW',repeat('a',64),repeat('b',64),repeat('c',64),'MATCH','MATCH','MATCH','MATCH')");
      execute(c,"insert into job_scores(job_id,score,band,suitable,formal_eligibility,java_backend,trainee_quality,supporting_technology,location_format,experience_compatibility,freshness,penalties,strengths,risks,hard_blockers,scored_at) values("+id+",75,'GOOD_MATCH',true,20,20,10,5,10,10,5,0,'Synthetic strength','','',CURRENT_TIMESTAMP)");
    }
    execute(c,"insert into applications(id,job_id,status,application_date,notes,created_at,updated_at,version) values(201,101,'SAVED',null,'Synthetic saved note','2026-01-02Z','2026-01-02Z',3),(202,102,'APPLIED','2026-01-03Z','Synthetic applied note','2026-01-02Z','2026-01-03Z',4)");
    execute(c,"insert into application_status_history(id,application_id,previous_status,new_status,changed_at,source) values(301,201,null,'SAVED','2026-01-02Z','INTERNAL'),(302,202,'SAVED','APPLIED','2026-01-03Z','TELEGRAM_COMMAND')");
    execute(c,"insert into job_workflow_state(job_id,status,note,applied_at,created_at,updated_at,version) values(101,'SAVED','Synthetic workflow note',null,'2026-01-02Z','2026-01-02Z',3),(102,'APPLIED',null,'2026-01-03Z','2026-01-02Z','2026-01-03Z',4)");
    execute(c,"update mini_app_state set mutation_revision=1");
    execute(c,"insert into mini_app_mutation(id,mutation_key,job_id,kind,requested_status,mutation_revision,changed,created_application,resulting_workflow_status,resulting_workflow_version,resulting_application_version,resulting_history_id,resulting_application_status,reversal_state,undo_token,created_at) values(501,'synthetic-original-mutation',101,'WORKFLOW','SAVED',1,true,false,'SAVED',3,3,301,'SAVED','REVERSIBLE',repeat('d',64),'2026-01-02Z')");
    execute(c,"insert into telegram_job_delivery(id,chat_id,job_id,delivery_type,delivered_at,created_at) values(401,900001,101,'MATCH_NOTIFICATION','2026-01-02Z','2026-01-02Z')");
    execute(c,"insert into telegram_bot_state(state_key,last_processed_update_id,updated_at) values('long-polling',1234,'2026-01-02Z')");
  }
}
