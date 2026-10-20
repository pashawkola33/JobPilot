ALTER TABLE job_workflow_state ADD COLUMN candidate_id BIGINT NOT NULL DEFAULT ${owner}
    REFERENCES candidates(id) ON DELETE RESTRICT;
-- Preserve the old job-key identity as the surrogate's initial value; job_id remains unchanged.
ALTER TABLE job_workflow_state ADD COLUMN id BIGINT;
UPDATE job_workflow_state SET id = job_id;
CREATE SEQUENCE job_workflow_state_id_seq START WITH ${workflowSequence};

ALTER TABLE job_workflow_state ALTER COLUMN id SET DEFAULT NEXT VALUE FOR job_workflow_state_id_seq;
ALTER TABLE job_workflow_state ALTER COLUMN id SET NOT NULL;
ALTER TABLE job_workflow_state DROP CONSTRAINT ${workflowJobFk};
ALTER TABLE job_workflow_state DROP CONSTRAINT ${workflowPk};
ALTER TABLE job_workflow_state ADD PRIMARY KEY (id);
ALTER TABLE job_workflow_state ADD CONSTRAINT workflow_shared_job_fk FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE CASCADE;
ALTER TABLE job_workflow_state ADD CONSTRAINT workflow_candidate_job_uk UNIQUE(candidate_id,job_id);

ALTER TABLE applications ADD COLUMN candidate_id BIGINT NOT NULL DEFAULT ${owner}
    REFERENCES candidates(id) ON DELETE RESTRICT;
ALTER TABLE applications DROP CONSTRAINT ${applicationJobFk};
ALTER TABLE applications DROP CONSTRAINT ${applicationJobUnique};
ALTER TABLE applications ADD CONSTRAINT applications_shared_job_fk FOREIGN KEY(job_id) REFERENCES jobs(id) ON DELETE RESTRICT;
ALTER TABLE applications ADD CONSTRAINT applications_candidate_job_uk UNIQUE(candidate_id,job_id);
ALTER TABLE applications ADD CONSTRAINT applications_candidate_id_uk UNIQUE(candidate_id,id);

ALTER TABLE application_status_history ADD COLUMN candidate_id BIGINT;
UPDATE application_status_history h SET candidate_id = a.candidate_id FROM applications a WHERE a.id=h.application_id;
ALTER TABLE application_status_history ALTER COLUMN candidate_id SET NOT NULL;
ALTER TABLE application_status_history ALTER COLUMN candidate_id SET DEFAULT ${owner};
ALTER TABLE application_status_history ADD CONSTRAINT history_candidate_application_fk
    FOREIGN KEY(candidate_id,application_id) REFERENCES applications(candidate_id,id) ON DELETE RESTRICT;
ALTER TABLE application_status_history ADD CONSTRAINT history_candidate_id_uk UNIQUE(candidate_id,id);

ALTER TABLE mini_app_state ADD COLUMN candidate_id BIGINT NOT NULL DEFAULT ${owner}
    REFERENCES candidates(id) ON DELETE RESTRICT;
ALTER TABLE mini_app_state DROP CONSTRAINT ${statePk};
ALTER TABLE mini_app_state ADD PRIMARY KEY(candidate_id);
-- id=1 is retained for legacy SQL compatibility; candidate_id now identifies the row.

ALTER TABLE mini_app_mutation ADD COLUMN candidate_id BIGINT NOT NULL DEFAULT ${owner}
    REFERENCES candidates(id) ON DELETE RESTRICT;
ALTER TABLE mini_app_mutation DROP CONSTRAINT ${mutationKeyUnique};
ALTER TABLE mini_app_mutation DROP CONSTRAINT ${mutationRevisionUnique};
ALTER TABLE mini_app_mutation ADD CONSTRAINT mutation_candidate_key_uk UNIQUE(candidate_id,mutation_key);
ALTER TABLE mini_app_mutation ADD CONSTRAINT mutation_candidate_revision_uk UNIQUE(candidate_id,mutation_revision);
ALTER TABLE mini_app_mutation ADD CONSTRAINT mutation_candidate_id_uk UNIQUE(candidate_id,id);
ALTER TABLE mini_app_mutation ADD CONSTRAINT mutation_candidate_history_fk
    FOREIGN KEY(candidate_id,created_history_id) REFERENCES application_status_history(candidate_id,id)
    ;
ALTER TABLE mini_app_mutation ADD CONSTRAINT mutation_candidate_reversed_fk
    FOREIGN KEY(candidate_id,reversed_by_mutation_id) REFERENCES mini_app_mutation(candidate_id,id)
    ;
CREATE INDEX mutation_candidate_job_idx ON mini_app_mutation(candidate_id,job_id,mutation_revision DESC);

-- Documents derive ownership from their immutable profile. The database protects all existing
-- selection paths, including background code and callers that do not expose documents in v2.
ALTER TABLE candidate_profiles ADD CONSTRAINT profile_candidate_id_uk UNIQUE(candidate_id,id);
ALTER TABLE resume_versions ADD COLUMN candidate_id BIGINT;
UPDATE resume_versions r SET candidate_id=p.candidate_id FROM candidate_profiles p WHERE p.id=r.candidate_profile_id;
ALTER TABLE resume_versions ALTER COLUMN candidate_id SET NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN candidate_id SET DEFAULT ${owner};
ALTER TABLE resume_versions ADD CONSTRAINT resume_candidate_profile_fk
    FOREIGN KEY(candidate_id,candidate_profile_id) REFERENCES candidate_profiles(candidate_id,id) ON DELETE RESTRICT;
ALTER TABLE resume_versions ADD CONSTRAINT resume_candidate_job_id_uk UNIQUE(candidate_id,job_id,id);
ALTER TABLE cover_notes ADD COLUMN candidate_id BIGINT;
UPDATE cover_notes n SET candidate_id=p.candidate_id FROM candidate_profiles p WHERE p.id=n.candidate_profile_id;
ALTER TABLE cover_notes ALTER COLUMN candidate_id SET NOT NULL;
ALTER TABLE cover_notes ALTER COLUMN candidate_id SET DEFAULT ${owner};
ALTER TABLE cover_notes ADD CONSTRAINT cover_candidate_profile_fk
    FOREIGN KEY(candidate_id,candidate_profile_id) REFERENCES candidate_profiles(candidate_id,id) ON DELETE RESTRICT;
ALTER TABLE cover_notes ADD CONSTRAINT cover_candidate_resume_fk
    FOREIGN KEY(candidate_id,job_id,resume_version_id) REFERENCES resume_versions(candidate_id,job_id,id)
    ;
ALTER TABLE cover_notes ADD CONSTRAINT cover_candidate_job_id_uk UNIQUE(candidate_id,job_id,id);
ALTER TABLE applications ADD CONSTRAINT application_candidate_resume_fk
    FOREIGN KEY(candidate_id,job_id,resume_version_id) REFERENCES resume_versions(candidate_id,job_id,id)
    ;
ALTER TABLE applications ADD CONSTRAINT application_candidate_cover_fk
    FOREIGN KEY(candidate_id,job_id,cover_note_id) REFERENCES cover_notes(candidate_id,job_id,id)
    ;

