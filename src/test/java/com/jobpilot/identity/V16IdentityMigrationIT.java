package com.jobpilot.identity;

import static org.assertj.core.api.Assertions.*;
import com.jobpilot.JobPilotApplication;
import com.jobpilot.miniapp.auth.TelegramInitDataFixture;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class V16IdentityMigrationIT {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    record Table(List<String> columns,List<List<String>> rows) { }

    private List<List<String>> rows(Connection c,String table,List<String> cols) throws SQLException {
        List<List<String>> rows=new ArrayList<>();
        String projection=cols.stream().map(x->"\""+x+"\"").reduce((a,b)->a+","+b).orElseThrow();
        try(var s=c.createStatement();var r=s.executeQuery("select "+projection+" from \""+table+"\"")) {
            while(r.next()) {List<String> row=new ArrayList<>();for(int i=1;i<=cols.size();i++) row.add(r.getString(i));rows.add(row);}
        }
        rows.sort(Comparator.comparing(Object::toString));return rows;
    }
    private Map<String,Table> snapshot(Connection c) throws SQLException {
        Map<String,Table> tables=new TreeMap<>();
        try(var s=c.createStatement();var r=s.executeQuery("select table_name from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name<>'flyway_schema_history'")) {
            while(r.next()) {
                String table=r.getString(1);List<String> cols=new ArrayList<>();
                try(var statement=c.createStatement();var rs=statement.executeQuery("select * from \""+table+"\" limit 0")) {
                    for(int i=1;i<=rs.getMetaData().getColumnCount();i++)cols.add(rs.getMetaData().getColumnName(i));
                }
                tables.put(table,new Table(cols,rows(c,table,cols)));
            }
        }
        return tables;
    }
    private void unchanged(Connection c,Map<String,Table> snapshot) throws SQLException {
        for(var entry:snapshot.entrySet()) assertThat(rows(c,entry.getKey(),entry.getValue().columns()))
                .as("all original rows/columns of %s",entry.getKey()).isEqualTo(entry.getValue().rows());
    }
    @Test void upgradesV13PreservingHistoryAndStartsWithLegacyOwnerThenEnforcesNewConstraints() throws Exception {
        var old=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("13").load();old.migrate();
        try(Connection c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword())) {
            LegacyMigrationFixture.seed(c);var before=snapshot(c);
            var checksums=new HashMap<String,Integer>();
            for(var migration:old.info().applied())checksums.put(migration.getVersion().toString(),migration.getChecksum());
            // Keep the Phase 1 assertion bounded; application startup below advances through current V17.
            var latest=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).target("16").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("16");
            assertThat(latest.validateWithResult().validationSuccessful).isTrue();
            for(var migration:latest.info().applied())if(checksums.containsKey(migration.getVersion().toString()))
                assertThat(migration.getChecksum()).isEqualTo(checksums.get(migration.getVersion().toString()));
            unchanged(c,before);assertThat(latest.migrate().migrationsExecuted).isZero();
            var app=new SpringApplication(JobPilotApplication.class);
            try(var ctx=app.run("--spring.config.additional-location=classpath:fixtures/identity-legacy-profile.yml",
                    "--spring.datasource.url="+DB.getJdbcUrl(),"--spring.datasource.username="+DB.getUsername(),"--spring.datasource.password="+DB.getPassword(),
                    "--server.port=0","--server.address=127.0.0.1","--jobpilot.scheduled-tasks-enabled=false",
                    "--jobpilot.identity.enabled=false","--jobpilot.identity.legacy-owner-telegram-user-id=4242",
                    "--jobpilot.mini-app.enabled=true","--jobpilot.mini-app.allowed-user-ids=4242",
                    "--jobpilot.telegram.bot-token="+TelegramInitDataFixture.BOT_TOKEN)) {
                assertThat(ctx.isActive()).isTrue();unchanged(c,before);
                var jdbc=ctx.getBean(JdbcTemplate.class);
                assertThat(jdbc.queryForObject("select count(*) from candidate_profiles p join candidates c on c.id=p.candidate_id where c.stable_key='default'",Integer.class)).isEqualTo(2);
                long owner=jdbc.queryForObject("select id from candidates where stable_key='default'",Long.class);
                jdbc.update("insert into telegram_identities values(4242,?,current_timestamp)",owner);
                assertThatThrownBy(()->jdbc.update("insert into telegram_identities values(4242,?,current_timestamp)",owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                assertThatThrownBy(()->jdbc.update("insert into telegram_identities values(9002,?,current_timestamp)",owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                assertThatThrownBy(()->jdbc.update("insert into auth_sessions values(repeat('a',64),9003,?,repeat('b',64),current_timestamp,current_timestamp+interval '15 minutes',null)",owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            }
        }
    }
}
