# Resume truth source — Stage 1

Stage 1 establishes the data source and audit relationships needed for truthful resume generation. It does not tailor or render resumes yet.

## Source of claims

`src/main/resources/candidate-profile.yml` is the only configured source of candidate claims. It records:

- versioned identity and education facts;
- zero commercial Java experience;
- verified technical skills with stable keys and evidence text;
- verified languages and whether each may appear in a CV;
- personal projects with stable keys, normalized technologies, and immutable verified bullet facts.

Contact data is intentionally absent. Later document generation must obtain optional contact values from local or environment configuration and must omit missing values.

## Version and evidence audit

Every stored profile has a positive `profile_version` and a source fingerprint. A changed fact set requires a higher version. Older versions and their fact IDs remain available.

The Stage 1 resume schema does not store selected skills or project claims as untraceable free text. It uses join rows pointing to:

- `candidate_skills.id`;
- `candidate_projects.id`;
- `candidate_project_bullets.id`.

`resume_versions.candidate_profile_id` and `profile_version` identify the exact truth snapshot used. Candidate facts referenced by a resume cannot be deleted because the foreign keys use `RESTRICT`.

Summary, preview, change-summary, and interview-claim fields are persistence destinations, not independent truth sources. Later-stage code must validate their structured fact references before rendering human-readable content.

## Current truth guards

The profile validator rejects blank or malformed required data, duplicate stable fact keys, duplicate active facts, negative commercial Java experience, French enabled for CV use, and Romanian configured as professional working fluency.

The committed profile contains only the facts supplied for Pavlo Sushkov. It contains no candidate email, phone number, fabricated metrics, employer experience, certifications, or unsupported languages.

## Deferred safeguards

`ResumeTailoringService`, `ResumeTruthfulnessValidator`, title/project selection, deterministic fallback text, content hashing, generated-path controls, DOCX creation, one-page PDF verification, and cover-note generation belong to Stage 5 and are not present in Stage 1.
