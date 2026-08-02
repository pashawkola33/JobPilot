ALTER TABLE jobs ADD COLUMN seniority_level VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE jobs ADD COLUMN experience_minimum_years DOUBLE PRECISION;
ALTER TABLE jobs ADD COLUMN experience_maximum_years DOUBLE PRECISION;
ALTER TABLE jobs ADD COLUMN experience_mandatory BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE jobs ADD COLUMN experience_raw_text TEXT;
ALTER TABLE jobs ADD COLUMN early_career_eligibility VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE jobs ADD COLUMN early_career_eligibility_reason VARCHAR(500) NOT NULL
    DEFAULT 'No seniority or experience requirement could be determined';

CREATE INDEX jobs_seniority_level_idx ON jobs(seniority_level);
CREATE INDEX jobs_early_career_eligibility_idx ON jobs(early_career_eligibility);
