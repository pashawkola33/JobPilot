ALTER TABLE resume_versions RENAME COLUMN content_hash TO structured_content_hash;
ALTER TABLE resume_versions ALTER COLUMN selected_title DROP NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN summary DROP NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN plain_text_preview DROP NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN change_summary DROP NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN interview_claims DROP NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN structured_content_hash DROP NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN generated_at DROP NOT NULL;
ALTER TABLE resume_versions ADD COLUMN source_analysis_id BIGINT
    REFERENCES job_analyses(id) ON DELETE SET NULL;
ALTER TABLE resume_versions ADD COLUMN docx_hash VARCHAR(64);
ALTER TABLE resume_versions ADD COLUMN docx_size BIGINT;
ALTER TABLE resume_versions ADD COLUMN pdf_hash VARCHAR(64);
ALTER TABLE resume_versions ADD COLUMN pdf_size BIGINT;
ALTER TABLE resume_versions ADD COLUMN pdf_page_count INTEGER;
ALTER TABLE resume_versions ADD COLUMN cache_key VARCHAR(64);
ALTER TABLE resume_versions ADD COLUMN template_version VARCHAR(80) NOT NULL DEFAULT 'legacy-v1';
ALTER TABLE resume_versions ADD COLUMN renderer_version VARCHAR(80) NOT NULL DEFAULT 'legacy-v1';
ALTER TABLE resume_versions ADD COLUMN requested_formats VARCHAR(40) NOT NULL DEFAULT 'NONE';
ALTER TABLE resume_versions ADD COLUMN generation_method VARCHAR(30) NOT NULL DEFAULT 'DETERMINISTIC';
ALTER TABLE resume_versions ADD COLUMN provider VARCHAR(120) NOT NULL DEFAULT 'disabled';
ALTER TABLE resume_versions ADD COLUMN model VARCHAR(200) NOT NULL DEFAULT 'disabled';
ALTER TABLE resume_versions ADD COLUMN fallback_used BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE resume_versions ADD COLUMN render_status VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS';
ALTER TABLE resume_versions ADD COLUMN failure_category VARCHAR(60);
ALTER TABLE resume_versions ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 1;
ALTER TABLE resume_versions ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE resume_versions ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE resume_versions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE resume_versions SET
    cache_key = LPAD(CAST(id AS VARCHAR), 64, '0'),
    created_at = generated_at,
    updated_at = generated_at,
    render_status = CASE WHEN docx_path IS NULL AND pdf_path IS NULL THEN 'COMPLETED' ELSE 'FAILED' END,
    failure_category = CASE WHEN docx_path IS NULL AND pdf_path IS NULL THEN NULL ELSE 'ARTIFACT_INVALID' END;

ALTER TABLE resume_versions ALTER COLUMN cache_key SET NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE resume_versions ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_cache_key_uk UNIQUE (cache_key);
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_generation_method_ck
    CHECK (generation_method IN ('DETERMINISTIC', 'PROVIDER'));
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_render_status_ck
    CHECK (render_status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'));
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_failure_category_ck CHECK (
    failure_category IS NULL OR failure_category IN
    ('CONFIGURATION', 'DRAFT_FAILED', 'TRUTH_VALIDATION', 'RENDER_FAILED',
     'ARTIFACT_INVALID', 'STORAGE_FAILED', 'DATABASE_FAILED', 'STALE_GENERATION')
);
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_formats_ck
    CHECK (requested_formats IN ('NONE', 'DOCX', 'PDF', 'DOCX,PDF'));
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_hashes_ck CHECK (
    CHAR_LENGTH(cache_key) = 64
    AND (structured_content_hash IS NULL OR CHAR_LENGTH(structured_content_hash) = 64)
    AND (docx_hash IS NULL OR CHAR_LENGTH(docx_hash) = 64)
    AND (pdf_hash IS NULL OR CHAR_LENGTH(pdf_hash) = 64)
);
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_docx_artifact_ck CHECK (
    render_status <> 'COMPLETED'
    OR (requested_formats NOT IN ('DOCX', 'DOCX,PDF')
        AND docx_path IS NULL AND docx_hash IS NULL AND docx_size IS NULL)
    OR (requested_formats IN ('DOCX', 'DOCX,PDF')
        AND docx_path IS NOT NULL AND docx_hash IS NOT NULL
        AND docx_size BETWEEN 1 AND 20971520
        AND POSITION('..' IN docx_path) = 0 AND POSITION(':' IN docx_path) = 0
        AND SUBSTRING(docx_path FROM 1 FOR 1) <> '/' AND SUBSTRING(docx_path FROM 1 FOR 1) <> '\\')
);
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_pdf_artifact_ck CHECK (
    render_status <> 'COMPLETED'
    OR (requested_formats NOT IN ('PDF', 'DOCX,PDF')
        AND pdf_path IS NULL AND pdf_hash IS NULL AND pdf_size IS NULL
        AND pdf_page_count IS NULL)
    OR (requested_formats IN ('PDF', 'DOCX,PDF')
        AND pdf_path IS NOT NULL AND pdf_hash IS NOT NULL
        AND pdf_size BETWEEN 1 AND 20971520 AND pdf_page_count BETWEEN 1 AND 2
        AND POSITION('..' IN pdf_path) = 0 AND POSITION(':' IN pdf_path) = 0
        AND SUBSTRING(pdf_path FROM 1 FOR 1) <> '/' AND SUBSTRING(pdf_path FROM 1 FOR 1) <> '\\')
);
ALTER TABLE resume_versions ADD CONSTRAINT resume_versions_lifecycle_ck CHECK (
    (render_status = 'IN_PROGRESS' AND generated_at IS NULL AND failure_category IS NULL)
    OR (render_status = 'FAILED' AND failure_category IS NOT NULL)
    OR (render_status = 'COMPLETED' AND generated_at IS NOT NULL AND failure_category IS NULL
        AND selected_title IS NOT NULL AND summary IS NOT NULL
        AND plain_text_preview IS NOT NULL AND change_summary IS NOT NULL
        AND interview_claims IS NOT NULL AND structured_content_hash IS NOT NULL)
);
CREATE INDEX resume_versions_analysis_idx ON resume_versions(source_analysis_id);
CREATE INDEX resume_versions_status_idx ON resume_versions(render_status, updated_at);

CREATE TABLE resume_version_languages (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    resume_version_id BIGINT NOT NULL REFERENCES resume_versions(id) ON DELETE CASCADE,
    candidate_language_id BIGINT NOT NULL REFERENCES candidate_languages(id) ON DELETE RESTRICT,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    CONSTRAINT resume_version_languages_fact_uk UNIQUE (resume_version_id, candidate_language_id),
    CONSTRAINT resume_version_languages_order_uk UNIQUE (resume_version_id, display_order)
);

ALTER TABLE cover_notes RENAME COLUMN content_hash TO structured_content_hash;
ALTER TABLE cover_notes ALTER COLUMN content DROP NOT NULL;
ALTER TABLE cover_notes ALTER COLUMN structured_content_hash DROP NOT NULL;
ALTER TABLE cover_notes ALTER COLUMN generated_at DROP NOT NULL;
ALTER TABLE cover_notes ADD COLUMN source_analysis_id BIGINT
    REFERENCES job_analyses(id) ON DELETE SET NULL;
ALTER TABLE cover_notes ADD COLUMN profile_version INTEGER;
ALTER TABLE cover_notes ADD COLUMN docx_path VARCHAR(1000);
ALTER TABLE cover_notes ADD COLUMN docx_hash VARCHAR(64);
ALTER TABLE cover_notes ADD COLUMN docx_size BIGINT;
ALTER TABLE cover_notes ADD COLUMN pdf_path VARCHAR(1000);
ALTER TABLE cover_notes ADD COLUMN pdf_hash VARCHAR(64);
ALTER TABLE cover_notes ADD COLUMN pdf_size BIGINT;
ALTER TABLE cover_notes ADD COLUMN pdf_page_count INTEGER;
ALTER TABLE cover_notes ADD COLUMN cache_key VARCHAR(64);
ALTER TABLE cover_notes ADD COLUMN template_version VARCHAR(80) NOT NULL DEFAULT 'legacy-v1';
ALTER TABLE cover_notes ADD COLUMN renderer_version VARCHAR(80) NOT NULL DEFAULT 'legacy-v1';
ALTER TABLE cover_notes ADD COLUMN requested_formats VARCHAR(40) NOT NULL DEFAULT 'NONE';
ALTER TABLE cover_notes ADD COLUMN generation_method VARCHAR(30) NOT NULL DEFAULT 'DETERMINISTIC';
ALTER TABLE cover_notes ADD COLUMN provider VARCHAR(120) NOT NULL DEFAULT 'disabled';
ALTER TABLE cover_notes ADD COLUMN model VARCHAR(200) NOT NULL DEFAULT 'disabled';
ALTER TABLE cover_notes ADD COLUMN fallback_used BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE cover_notes ADD COLUMN render_status VARCHAR(30) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE cover_notes ADD COLUMN failure_category VARCHAR(60);
ALTER TABLE cover_notes ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 1;
ALTER TABLE cover_notes ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE cover_notes ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE cover_notes ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE cover_notes SET
    profile_version = (SELECT profile_version FROM candidate_profiles
                       WHERE candidate_profiles.id = cover_notes.candidate_profile_id),
    cache_key = LPAD(CAST(id AS VARCHAR), 64, '0'),
    created_at = generated_at,
    updated_at = generated_at;

ALTER TABLE cover_notes ALTER COLUMN profile_version SET NOT NULL;
ALTER TABLE cover_notes ALTER COLUMN cache_key SET NOT NULL;
ALTER TABLE cover_notes ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE cover_notes ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_cache_key_uk UNIQUE (cache_key);
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_generation_method_ck
    CHECK (generation_method IN ('DETERMINISTIC', 'PROVIDER'));
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_render_status_ck
    CHECK (render_status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'));
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_failure_category_ck CHECK (
    failure_category IS NULL OR failure_category IN
    ('CONFIGURATION', 'DRAFT_FAILED', 'TRUTH_VALIDATION', 'RENDER_FAILED',
     'ARTIFACT_INVALID', 'STORAGE_FAILED', 'DATABASE_FAILED', 'STALE_GENERATION')
);
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_formats_ck
    CHECK (requested_formats IN ('NONE', 'DOCX', 'PDF', 'DOCX,PDF'));
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_hashes_ck CHECK (
    CHAR_LENGTH(cache_key) = 64
    AND (structured_content_hash IS NULL OR CHAR_LENGTH(structured_content_hash) = 64)
    AND (docx_hash IS NULL OR CHAR_LENGTH(docx_hash) = 64)
    AND (pdf_hash IS NULL OR CHAR_LENGTH(pdf_hash) = 64)
);
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_docx_artifact_ck CHECK (
    render_status <> 'COMPLETED'
    OR (requested_formats NOT IN ('DOCX', 'DOCX,PDF')
        AND docx_path IS NULL AND docx_hash IS NULL AND docx_size IS NULL)
    OR (requested_formats IN ('DOCX', 'DOCX,PDF')
        AND docx_path IS NOT NULL AND docx_hash IS NOT NULL
        AND docx_size BETWEEN 1 AND 20971520
        AND POSITION('..' IN docx_path) = 0 AND POSITION(':' IN docx_path) = 0
        AND SUBSTRING(docx_path FROM 1 FOR 1) <> '/' AND SUBSTRING(docx_path FROM 1 FOR 1) <> '\\')
);
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_pdf_artifact_ck CHECK (
    render_status <> 'COMPLETED'
    OR (requested_formats NOT IN ('PDF', 'DOCX,PDF')
        AND pdf_path IS NULL AND pdf_hash IS NULL AND pdf_size IS NULL
        AND pdf_page_count IS NULL)
    OR (requested_formats IN ('PDF', 'DOCX,PDF')
        AND pdf_path IS NOT NULL AND pdf_hash IS NOT NULL
        AND pdf_size BETWEEN 1 AND 20971520 AND pdf_page_count BETWEEN 1 AND 2
        AND POSITION('..' IN pdf_path) = 0 AND POSITION(':' IN pdf_path) = 0
        AND SUBSTRING(pdf_path FROM 1 FOR 1) <> '/' AND SUBSTRING(pdf_path FROM 1 FOR 1) <> '\\')
);
ALTER TABLE cover_notes ADD CONSTRAINT cover_notes_lifecycle_ck CHECK (
    (render_status = 'IN_PROGRESS' AND generated_at IS NULL AND failure_category IS NULL)
    OR (render_status = 'FAILED' AND failure_category IS NOT NULL)
    OR (render_status = 'COMPLETED' AND generated_at IS NOT NULL AND failure_category IS NULL
        AND content IS NOT NULL AND structured_content_hash IS NOT NULL)
);
CREATE INDEX cover_notes_analysis_idx ON cover_notes(source_analysis_id);
CREATE INDEX cover_notes_status_idx ON cover_notes(render_status, updated_at);

CREATE TABLE cover_note_fact_references (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cover_note_id BIGINT NOT NULL REFERENCES cover_notes(id) ON DELETE CASCADE,
    fact_type VARCHAR(30) NOT NULL,
    fact_key VARCHAR(100) NOT NULL CHECK (TRIM(fact_key) <> ''),
    candidate_profile_id BIGINT REFERENCES candidate_profiles(id) ON DELETE RESTRICT,
    candidate_skill_id BIGINT REFERENCES candidate_skills(id) ON DELETE RESTRICT,
    candidate_language_id BIGINT REFERENCES candidate_languages(id) ON DELETE RESTRICT,
    candidate_project_id BIGINT REFERENCES candidate_projects(id) ON DELETE RESTRICT,
    candidate_project_bullet_id BIGINT REFERENCES candidate_project_bullets(id) ON DELETE RESTRICT,
    display_order INTEGER NOT NULL CHECK (display_order >= 0),
    CONSTRAINT cover_note_fact_type_ck CHECK (
        fact_type IN ('PROFILE', 'SKILL', 'LANGUAGE', 'PROJECT', 'PROJECT_BULLET')),
    CONSTRAINT cover_note_fact_exactly_one_ck CHECK (
        (CASE WHEN candidate_profile_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN candidate_skill_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN candidate_language_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN candidate_project_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN candidate_project_bullet_id IS NULL THEN 0 ELSE 1 END) = 1
    ),
    CONSTRAINT cover_note_fact_key_uk UNIQUE (cover_note_id, fact_type, fact_key),
    CONSTRAINT cover_note_fact_order_uk UNIQUE (cover_note_id, display_order)
);
