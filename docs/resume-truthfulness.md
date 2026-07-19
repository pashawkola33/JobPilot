# Resume truth source — Stage 5

Stage 5 implements tailoring and rendering while keeping the versioned Stage 1 profile as the candidate-claim authority.

## Source of claims

`src/main/resources/candidate-profile.yml` is the only configured source of candidate claims. It records:

- versioned identity and education facts;
- zero commercial Java experience;
- verified technical skills with stable keys and evidence text;
- verified languages and whether each may appear in a CV;
- personal projects with stable keys, normalized technologies, and immutable verified bullet facts.

Contact data is intentionally absent. Stage 5 obtains it from runtime-only document configuration, validates it, sends none of it to an LLM, and injects it only while rendering private DOCX/PDF files. It is absent from database fields, previews, change/interview summaries, canonical content hashes, and logs. Only an opaque, domain-separated HMAC-SHA256 contact identity influences the persisted cache key; its independent runtime secret and raw contact values are never persisted or prompted.

## Version and evidence audit

Every stored profile has a positive `profile_version` and a source fingerprint. A changed fact set requires a higher version. Older versions and their fact IDs remain available.

The Stage 1 resume schema does not store selected skills or project claims as untraceable free text. It uses join rows pointing to:

- `candidate_skills.id`;
- `candidate_projects.id`;
- `candidate_project_bullets.id`.

`resume_versions.candidate_profile_id` and `profile_version` identify the exact truth snapshot used. Candidate facts referenced by a resume cannot be deleted because the foreign keys use `RESTRICT`.

Summary, preview, change-summary, and interview-claim fields are persistence destinations, not independent truth sources. Later-stage code must validate their structured fact references before rendering human-readable content.

## Selection and rendering guards

The profile validator rejects blank or malformed required data, duplicate stable fact keys, duplicate active facts, negative commercial Java experience, French enabled for CV use, and Romanian configured as professional working fluency. Stage 5 additionally rejects inactive/CV-disallowed selections, unknown IDs or stable keys, duplicate normalized skills, unsupported title styles, changed language levels, changed project bullets, unrelated prose, invented metrics/employers/achievements, theoretical-to-practical strengthening, and commercial/work-experience implications unsupported by the profile.

The deterministic selector ranks only verified active skills, languages, projects, and bullet rows using vacancy/analysis terms and stored keywords. It uses stable ordering and bounded selection counts. The summary and cover-note paragraphs are reconstructed from verified values after plan validation. The renderer cannot invent content because both DOCX and PDF consume the same validated canonical model.

The committed profile contains only the supplied verified facts. It contains no candidate email, phone number, fabricated metrics, employer experience, certifications, or unsupported languages. With zero verified commercial experience, Stage 5 emits no work-experience section and uses student/project language.

## Optional provider boundary

Optional provider drafting returns bounded stable-key selection plans, never canonical free prose. Résumé and cover-note operations have separate request/cache identities and reuse Stage 4 reservations and usage accounting. Vacancy text remains untrusted prompt data. Unsupported, malformed, over-budget, disabled, or failed provider paths use the deterministic model and are explicitly marked fallback.

## Audit and limitations

`resume_version_skills`, `resume_version_projects`, `resume_version_project_bullets`, and `resume_version_languages` reference the exact selected fact rows. `cover_note_fact_references` records each candidate fact used in the note. A structured-content SHA-256, template/renderer/provider/model identities, source analysis, exact profile version, and artifact metadata preserve the generation audit without persisting contact data.

These checks materially reduce unsupported claims but cannot guarantee perfect hallucination prevention or universal ATS compatibility. Generated documents require human review and are never automatically submitted, uploaded, sent to recruiters, or used to answer screening questions.
