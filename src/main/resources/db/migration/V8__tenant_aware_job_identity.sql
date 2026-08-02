ALTER TABLE jobs ADD COLUMN provider_tenant VARCHAR(300) NOT NULL DEFAULT 'legacy';

DROP INDEX jobs_source_external_uk;
CREATE UNIQUE INDEX jobs_source_tenant_external_uk
    ON jobs(source, provider_tenant, external_id);

ALTER TABLE jobs ADD CONSTRAINT jobs_location_eligibility_ck CHECK (
    location_eligibility IN ('BUCHAREST_LOCAL', 'REMOTE_ROMANIA_ELIGIBLE',
        'REMOTE_ELIGIBILITY_UNKNOWN', 'REJECTED_LOCATION'));
ALTER TABLE jobs ADD CONSTRAINT jobs_remote_scope_ck CHECK (
    remote_scope IN ('ROMANIA', 'EU', 'EEA', 'EUROPE', 'EMEA', 'WORLDWIDE',
        'COUNTRY_RESTRICTED', 'REGION_RESTRICTED', 'UNKNOWN'));
ALTER TABLE jobs ADD CONSTRAINT jobs_seniority_level_ck CHECK (
    seniority_level IN ('INTERNSHIP', 'TRAINEE', 'WORKING_STUDENT', 'GRADUATE',
        'ENTRY_LEVEL', 'JUNIOR', 'MID_LEVEL', 'SENIOR', 'LEADERSHIP', 'UNKNOWN'));
ALTER TABLE jobs ADD CONSTRAINT jobs_early_career_eligibility_ck CHECK (
    early_career_eligibility IN ('ELIGIBLE', 'INELIGIBLE', 'UNKNOWN'));
