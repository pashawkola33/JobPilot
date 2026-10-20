-- Preferences are independent of verified candidate facts and legacy bootstrap.
CREATE TABLE search_profile_revisions (
    candidate_id BIGINT NOT NULL REFERENCES candidates(id) ON DELETE RESTRICT,
    revision BIGINT NOT NULL CHECK (revision > 0),
    expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0 AND revision = expected_revision + 1),
    request_id VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY(candidate_id, revision),
    UNIQUE(candidate_id, request_id)
);
CREATE TABLE search_profile_state (
    candidate_id BIGINT PRIMARY KEY REFERENCES candidates(id) ON DELETE RESTRICT,
    active_revision BIGINT NOT NULL,
    CONSTRAINT search_profile_active_owner_fk FOREIGN KEY(candidate_id, active_revision)
        REFERENCES search_profile_revisions(candidate_id, revision) ON DELETE RESTRICT
);
