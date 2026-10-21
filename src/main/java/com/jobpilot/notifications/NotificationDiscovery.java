package com.jobpilot.notifications;
import java.sql.Timestamp;import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.core.env.Environment;import org.springframework.stereotype.Component;import org.springframework.transaction.annotation.*;
@Component
public class NotificationDiscovery {
 private final JdbcTemplate jdbc;private final Clock clock;private final Environment env;
 public NotificationDiscovery(JdbcTemplate jdbc,Clock clock,Environment env){this.jdbc=jdbc;this.clock=clock;this.env=env;}
 /** Actual shared-job insert only. Catalogue capture must precede this final barrier operation. */
 @Transactional(propagation=Propagation.MANDATORY) public void inserted(long job) {
  if(java.util.Arrays.asList(env.getActiveProfiles()).contains("recovery"))return;
  long revision=jdbc.queryForObject("select change_revision from matching_catalogue_state where id=1 for update",Long.class);
  jdbc.update("insert into notification_discoveries(job_id,discovery_revision,discovered_at) select ?,?,? where not exists(select 1 from notification_discoveries where job_id=?)",job,revision,Timestamp.from(clock.instant()),job);
 }
}
