CREATE FUNCTION reject_matching_history_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Matching history is immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER vacancy_fact_history_immutable BEFORE UPDATE OR DELETE ON vacancy_fact_revisions FOR EACH ROW EXECUTE FUNCTION reject_matching_history_change();
CREATE TRIGGER candidate_match_history_immutable BEFORE UPDATE OR DELETE ON candidate_match_results FOR EACH ROW EXECUTE FUNCTION reject_matching_history_change();
CREATE TRIGGER catalogue_change_history_immutable BEFORE UPDATE OR DELETE ON matching_catalogue_changes FOR EACH ROW EXECUTE FUNCTION reject_matching_history_change();

-- PostgreSQL can scan the exact recommendation order without sorting a candidate's history.
DROP INDEX candidate_match_listing_idx;
CREATE INDEX candidate_match_listing_idx ON candidate_match_results(candidate_id,search_profile_version,extractor_version,scorer_version,(CASE eligibility WHEN 'ELIGIBLE' THEN 0 WHEN 'UNKNOWN' THEN 1 ELSE 2 END),compatibility_score DESC,published_at DESC,job_id DESC);
