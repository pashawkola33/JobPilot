package com.jobpilot.support;
import org.springframework.jdbc.core.JdbcTemplate;
/** Disposable test fixtures only: production histories deliberately prevent job deletion. */
public final class MatchingTestCleanup {
 private MatchingTestCleanup() { }
 public static void clear(JdbcTemplate jdbc) {
  boolean postgres=Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c->c.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
  String tables="candidate_match_current,candidate_match_results,candidate_matching_state,matching_catalogue_changes,vacancy_fact_state,vacancy_fact_revisions";
  if(postgres)jdbc.execute("truncate "+tables);
  else for(String table:tables.split(","))jdbc.update("delete from "+table);
  jdbc.update("update matching_catalogue_state set change_revision=0");
 }
}
