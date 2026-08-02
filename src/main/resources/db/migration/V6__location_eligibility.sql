ALTER TABLE jobs ADD COLUMN location_eligibility VARCHAR(50) NOT NULL DEFAULT 'REMOTE_ELIGIBILITY_UNKNOWN';
ALTER TABLE jobs ADD COLUMN remote_scope VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE jobs ADD COLUMN normalized_city VARCHAR(150);
ALTER TABLE jobs ADD COLUMN normalized_country VARCHAR(150);
ALTER TABLE jobs ADD COLUMN eligible_from_romania BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE jobs ADD COLUMN eligibility_reason VARCHAR(500) NOT NULL DEFAULT 'Eligibility was not evaluated';
ALTER TABLE jobs ADD COLUMN detected_location_restrictions TEXT;
ALTER TABLE jobs ADD COLUMN required_timezone VARCHAR(300);
ALTER TABLE jobs ADD COLUMN required_work_authorization VARCHAR(500);

CREATE INDEX jobs_location_eligibility_idx ON jobs(location_eligibility);
CREATE INDEX jobs_remote_scope_idx ON jobs(remote_scope);
