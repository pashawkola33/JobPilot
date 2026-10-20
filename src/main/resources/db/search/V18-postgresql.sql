CREATE FUNCTION reject_search_profile_revision_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Search profile revisions are immutable' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER search_profile_revisions_immutable BEFORE UPDATE OR DELETE ON search_profile_revisions
    FOR EACH ROW EXECUTE FUNCTION reject_search_profile_revision_change();
