package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.*;

import com.jobpilot.JobPilotApplication;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Deployed V13 upgrade rehearsal comparing every original column and row. */
@Testcontainers
class V19MatchingMigrationIT {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    record Table(List<String> columns, List<List<String>> rows) { }

    private List<List<String>> rows(Connection c, String table, List<String> columns) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        String projection = columns.stream().map(x -> "\""+x+"\"").reduce((a,b)->a+","+b).orElseThrow();
        try(var s=c.createStatement();var r=s.executeQuery("select "+projection+" from \""+table+"\"")) {
            while(r.next()) {
                List<String> row=new ArrayList<>();
                for(int i=1;i<=columns.size();i++) row.add(r.getString(i));
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing(Object::toString)); return rows;
    }
    private Map<String,Table> snapshot(Connection c) throws SQLException {
        Map<String,Table> tables=new TreeMap<>();
        try(var s=c.createStatement();var r=s.executeQuery("select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name<>'flyway_schema_history'")) {
            while(r.next()) {
                String table=r.getString(1); List<String> cols=new ArrayList<>();
                try(var statement=c.createStatement();var rs=statement.executeQuery("select * from \""+table+"\" limit 0")) {
                    for(int i=1;i<=rs.getMetaData().getColumnCount();i++) cols.add(rs.getMetaData().getColumnName(i));
                }
                tables.put(table,new Table(cols,rows(c,table,cols)));
            }
        }
        return tables;
    }
    private void unchanged(Connection c, Map<String,Table> before) throws SQLException {
        for(var table:before.entrySet()) assertThat(rows(c,table.getKey(),table.getValue().columns()))
                .as("original IDs, versions, timestamps and values in %s",table.getKey())
                .isEqualTo(table.getValue().rows());
    }
    private void execute(Connection c, String sql) throws SQLException { try(var s=c.createStatement()){s.execute(sql);} }
    private long scalar(Connection c,String sql) throws SQLException {try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);} }
    private void rejected(Connection c,String sql) {
        assertThatThrownBy(()->execute(c,sql)).isInstanceOf(SQLException.class)
                .satisfies(error->assertThat(((SQLException)error).getSQLState()).isEqualTo("23503"));
    }

    @Test void v13ThroughV19PreservesHistoryAndEnforcesCandidateRelationships() throws Exception {
        var v13=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("13").load();v13.migrate();
        try(var c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            LegacyMigrationFixture.seed(c);
            execute(c,"insert into resume_versions(id,job_id,candidate_profile_id,profile_version,cache_key,created_at,updated_at) values(601,101,2,2,repeat('e',64),'2026-01-02Z','2026-01-02Z')");
            execute(c,"insert into cover_notes(id,job_id,candidate_profile_id,resume_version_id,profile_version,cache_key,render_status,created_at,updated_at) values(602,101,2,601,2,repeat('f',64),'IN_PROGRESS','2026-01-02Z','2026-01-02Z')");
            execute(c,"update applications set resume_version_id=601,cover_note_id=602 where id=201");
            var before=snapshot(c);
            var v16=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("16").load();v16.migrate();
            var checksums=new HashMap<String,Integer>();
            for(var m:v16.info().applied()) checksums.put(m.getVersion().toString(),m.getChecksum());
            // Beta registers before the owner exchanges a credential; it must not inherit history.
            execute(c,"insert into candidates(stable_key,created_at,updated_at) values('account-migration-beta',current_timestamp,current_timestamp)");
            long beta=scalar(c,"select id from candidates where stable_key='account-migration-beta'");
            execute(c,"insert into telegram_identities values(9002,"+beta+",current_timestamp)");
            long owner=scalar(c,"select id from candidates where stable_key='default'");
            execute(c,"insert into telegram_identities values(4242,"+owner+",current_timestamp)");
            Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("17").load().migrate();
            for(var m:Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("17").load().info().applied()) checksums.put(m.getVersion().toString(),m.getChecksum());
            var prior=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("18").load();prior.migrate();
            for(var m:prior.info().applied())checksums.put(m.getVersion().toString(),m.getChecksum());
            execute(c,"insert into search_profile_revisions(candidate_id,revision,expected_revision,request_id,payload,created_at) values("+beta+",1,0,'synthetic-profile-1','{}',current_timestamp)");
            execute(c,"insert into search_profile_state(candidate_id,active_revision) values("+beta+",1)");
            var profilesBefore=rows(c,"search_profile_revisions",List.of("candidate_id","revision","expected_revision","request_id","payload","completed","created_at"));
            var latest=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("19");
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            for(var m:latest.info().applied()) if(checksums.containsKey(m.getVersion().toString()))
                assertThat(m.getChecksum()).as("unchanged applied checksum V%s",m.getVersion()).isEqualTo(checksums.get(m.getVersion().toString()));
            unchanged(c,before);
            for(String table:List.of("job_workflow_state","applications","application_status_history","mini_app_state","mini_app_mutation","resume_versions","cover_notes")) {
                assertThat(scalar(c,"select count(*) from "+table+" where candidate_id<>"+owner)).as("legacy owner of %s",table).isZero();
            }
            assertThat(latest.migrate().migrationsExecuted).isZero();
            var app=new SpringApplication(JobPilotApplication.class);
            try(var context=app.run("--spring.config.additional-location=classpath:fixtures/identity-legacy-profile.yml",
                    "--spring.datasource.url="+DB.getJdbcUrl(),"--spring.datasource.username="+DB.getUsername(),"--spring.datasource.password="+DB.getPassword(),
                    "--server.port=0","--server.address=127.0.0.1","--jobpilot.scheduled-tasks-enabled=false",
                    "--jobpilot.identity.enabled=true","--jobpilot.identity.registration-enabled=false",
                    "--jobpilot.identity.legacy-owner-telegram-user-id=4242","--jobpilot.mini-app.enabled=true",
                    "--jobpilot.mini-app.allowed-user-ids=4242","--jobpilot.telegram.bot-token="+TelegramInitDataFixture.BOT_TOKEN)) {
                assertThat(context.isActive()).isTrue(); unchanged(c,before);
            }
            assertThat(rows(c,"search_profile_revisions",List.of("candidate_id","revision","expected_revision","request_id","payload","completed","created_at"))).isEqualTo(profilesBefore);
            rejected(c,"insert into search_profile_state(candidate_id,active_revision) values("+owner+",1)");
            assertThatThrownBy(()->execute(c,"update search_profile_revisions set payload='{}' where candidate_id="+beta)).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->execute(c,"delete from search_profile_revisions where candidate_id="+beta)).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->execute(c,"insert into search_profile_revisions(candidate_id,revision,expected_revision,request_id,payload,created_at) values("+beta+",2,1,'synthetic-profile-1','{}',current_timestamp)")).isInstanceOf(SQLException.class);
            assertThat(scalar(c,"select active_revision from search_profile_state where candidate_id="+beta)).isEqualTo(1);
            execute(c,"insert into job_workflow_state(candidate_id,job_id,status,created_at,updated_at) values("+beta+",101,'DISMISSED',current_timestamp,current_timestamp)");
            execute(c,"insert into applications(candidate_id,job_id,status,created_at,updated_at) values("+beta+",101,'SAVED',current_timestamp,current_timestamp)");
            long application=scalar(c,"select id from applications where candidate_id="+beta+" and job_id=101");
            // Database checks protect even paths that do not publish documents in v2.
            rejected(c,"update applications set resume_version_id=601 where id="+application);
            rejected(c,"update applications set cover_note_id=602 where id="+application);
            rejected(c,"insert into application_status_history(candidate_id,application_id,new_status,changed_at,source) values("+beta+",201,'SAVED',current_timestamp,'INTERNAL')");
            rejected(c,"update resume_versions set candidate_id="+beta+" where id=601");
            execute(c,"insert into mini_app_mutation(candidate_id,mutation_key,job_id,kind,requested_status,mutation_revision,changed,created_application,reversal_state,created_at) values("+beta+",'synthetic-original-mutation',101,'WORKFLOW','SAVED',1,false,false,'NOT_REVERSIBLE',current_timestamp)");
            assertThat(scalar(c,"select count(*) from mini_app_mutation where mutation_key='synthetic-original-mutation'")).isEqualTo(2);
            rejected(c,"update mini_app_mutation set created_history_id=301 where candidate_id="+beta);
        }
    }
}
