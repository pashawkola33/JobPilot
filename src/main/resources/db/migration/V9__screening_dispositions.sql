ALTER TABLE jobs ADD COLUMN screening_disposition VARCHAR(20) NOT NULL DEFAULT 'MATCH';
ALTER TABLE jobs ADD COLUMN location_disposition VARCHAR(20) NOT NULL DEFAULT 'MATCH';
ALTER TABLE jobs ADD COLUMN career_disposition VARCHAR(20) NOT NULL DEFAULT 'MATCH';
ALTER TABLE jobs ADD COLUMN relevance_disposition VARCHAR(20) NOT NULL DEFAULT 'MATCH';
ALTER TABLE jobs ADD COLUMN screening_reasons JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE jobs SET location_disposition = CASE
    WHEN location_eligibility = 'REJECTED_LOCATION' THEN 'REJECT'
    WHEN location_eligibility = 'REMOTE_ELIGIBILITY_UNKNOWN' THEN 'REVIEW'
    ELSE 'MATCH'
END;

UPDATE jobs SET career_disposition = CASE
    WHEN early_career_eligibility = 'INELIGIBLE' THEN 'REJECT'
    WHEN early_career_eligibility = 'UNKNOWN' THEN 'REVIEW'
    ELSE 'MATCH'
END;

UPDATE jobs SET screening_disposition = CASE
    WHEN location_disposition = 'REJECT'
        OR career_disposition = 'REJECT'
        OR relevance_disposition = 'REJECT' THEN 'REJECT'
    WHEN location_disposition = 'REVIEW'
        OR career_disposition = 'REVIEW'
        OR relevance_disposition = 'REVIEW' THEN 'REVIEW'
    ELSE 'MATCH'
END;

ALTER TABLE jobs ALTER COLUMN screening_disposition SET DEFAULT 'REVIEW';
ALTER TABLE jobs ALTER COLUMN location_disposition SET DEFAULT 'REVIEW';
ALTER TABLE jobs ALTER COLUMN career_disposition SET DEFAULT 'REVIEW';
ALTER TABLE jobs ALTER COLUMN relevance_disposition SET DEFAULT 'REVIEW';

ALTER TABLE jobs ADD CONSTRAINT jobs_screening_disposition_ck CHECK (
    screening_disposition IN ('MATCH', 'REVIEW', 'REJECT'));
ALTER TABLE jobs ADD CONSTRAINT jobs_location_disposition_ck CHECK (
    location_disposition IN ('MATCH', 'REVIEW', 'REJECT'));
ALTER TABLE jobs ADD CONSTRAINT jobs_career_disposition_ck CHECK (
    career_disposition IN ('MATCH', 'REVIEW', 'REJECT'));
ALTER TABLE jobs ADD CONSTRAINT jobs_relevance_disposition_ck CHECK (
    relevance_disposition IN ('MATCH', 'REVIEW', 'REJECT'));

CREATE INDEX jobs_screening_disposition_idx ON jobs(screening_disposition);
