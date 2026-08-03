# Phase 3.3C specification: SmartRecruiters provider adapter

Status: **implementation-ready design; implementation has not started**.

## Selection and scope

Implement one generic **SmartRecruiters Posting API** adapter. Initial confirmed employer
coordinates are `BoschGroup`, `Ubisoft2`, `Endava`, `Gameloft`, and `AECOM2`; they are
evidence for the design, not hard-coded branches. Production registry additions and live
validation belong to Phase 3.3D, not 3.3C.

The adapter uses only public, unauthenticated JSON over HTTPS. It does not apply to jobs,
render pages, store cookies, use a browser, accept API keys, or scrape career-site HTML.

## Public contract

| Concern | Contract |
|---|---|
| List | [`GET https://api.smartrecruiters.com/v1/companies/{companyIdentifier}/postings`](https://developers.smartrecruiters.com/docs/endpoints) |
| Detail | `GET https://api.smartrecruiters.com/v1/companies/{companyIdentifier}/postings/{postingId}` |
| Method/body | GET; no request body |
| Required headers | Existing safe `Accept: application/json` and JobPilot user agent only |
| Authentication/cookies | None; never send either |
| List parameters | `limit=100`, `offset=<non-negative>`, plus the bounded query partitions below |
| Response | JSON; list has `limit`, `offset`, `totalFound`, `content`; detail is one posting |
| Stable identity | Detail/list `id`, exact string, scoped by provider tenant |

SmartRecruiters documentation describes list fields as potentially incomplete, so every
unique list item must be hydrated from the detail endpoint before a `RawJob` is emitted.

## Discovery partitions and completeness boundary

Fetching every global job from boards with 4,000+ postings would create thousands of detail
requests every six hours while adding mainly ineligible geography. The generic adapter will
therefore query two profile-level partitions for every configured company, in fixed order:

1. `country=<jobpilot.eligibility.target-country as ISO-3166 alpha-2>`; initially `ro`.
2. `q=remote`; results are candidates only and still pass the existing strict remote-scope
   screening. A remote flag alone never establishes Romania eligibility.

This is provider-generic and employer-independent. The target-country conversion is a closed
mapping validated at startup; Phase 3.3C needs only Romania -> `ro`. Unsupported configured
countries fail startup rather than silently broadening access. The remote text query is a
documented public query parameter but is best-effort; it does not claim complete discovery of
every Europe-remote role. Broader remote-query semantics or multi-country expansion is out of
scope until live evidence justifies it.

Results from both partitions are unioned by posting `id` before details are requested. The
same posting returned by both partitions produces one detail request and one `RawJob`.

## Pagination and request safety

Constants for the first implementation:

- `PAGE_SIZE = 100`;
- `MAX_LIST_PAGES_PER_TENANT = 10` across both partitions;
- `MAX_UNIQUE_JOBS_PER_TENANT = 500` across both partitions;
- the existing `JOBPILOT_HTTP_MAX_RESPONSE_BYTES=10485760` remains unchanged for every
  list and detail response;
- requests are sequential per tenant; no parallel fan-out;
- maximum successful physical calls before transport retries: 10 list + 500 detail = 510.

Algorithm for one company:

1. Enter one `TenantFetchMonitor.fetch("smartrecruiters", companyIdentifier, supplier)`.
2. For each partition in fixed order, request offset 0 and read `limit`, `offset`,
   `totalFound`, and `content`.
3. Require returned offset to equal requested offset; require `limit` in 1..100; require
   non-negative, bounded `totalFound`; require content size <= returned limit.
4. Add summaries to insertion-ordered map by required posting `id`. Repeated IDs are benign
   duplicates only when immutable identity fields do not conflict; conflicting duplicates
   are schema errors.
5. Fingerprint each page from its ordered posting IDs. A repeated non-empty fingerprint,
   repeated requested offset, or no offset advance fails the tenant.
6. Stop a partition on an empty page, when `offset + content.size >= totalFound`, or when a
   short page is consistent with the total. A changing total is tolerated only when it still
   makes monotonic progress; impossible/negative totals or a page beyond the current total
   fail schema validation.
7. Before every next page, enforce the shared 10-page and 500-unique-job caps. Hitting a cap
   while the provider says more data remains fails the tenant; it is not reported as a
   complete success.
8. Hydrate each unique posting sequentially. Require the detail identity to equal the list
   identity and reject missing required fields.
9. Only after every page and detail succeeds, return the complete immutable list to the
   monitor.

Offset pagination has no opaque token, so “repeated token” protection is implemented as
repeated-offset plus repeated-page-ID fingerprint detection. The page count and unique-job
count must use overflow-safe arithmetic.

### Partial failure policy

Use **all-or-nothing tenant semantics**. If a later page or any required detail fails, discard
all accumulated results for that company and let `TenantFetchMonitor` record one FAILURE with
zero fetched jobs. Retaining a partial set would let reconciliation treat unseen active jobs
as absent and would falsely mark a truncated board healthy. This matches the current adapter
contract, which returns one list only after its supplier completes.

## Transport, retries, and destinations

Extend `ExternalHttpClient` in Phase 3.3C with an exact
`api.smartrecruiters.com` destination family. HTTPS, default port, no userinfo/fragment,
public DNS answers, the existing five-redirect cap, and same-provider-family redirect checks
remain mandatory. Do not allow wildcard `*.smartrecruiters.com` request destinations.

Each physical GET uses existing retry semantics: at most three attempts; retry timeout, I/O,
429, and 5xx; honor bounded numeric `Retry-After`; do not retry other 4xx, invalid destination,
invalid content type, malformed JSON, schema failure, or response overflow. The aggregate
page/job caps are deterministic and non-retryable.

## DTO contract

Use provider-local DTOs or strict parsing with equivalent validation.

`PostingPageDto`:

- `int limit`, `int offset`, `int totalFound`;
- non-null `List<PostingSummaryDto> content`.

`PostingSummaryDto`:

- required nonblank `id`, `name`;
- optional `uuid`;
- `company.identifier`, `company.name`;
- `releasedDate`;
- `location.city`, `location.region`, `location.country`, `location.remote`;
- department, function, employment type, and experience-level ID/label pairs;
- optional `ref` used only for consistency checking, never as a request destination.

`PostingDetailDto`:

- all summary identity and metadata fields;
- required `postingUrl` (with `applyUrl` retained only as non-canonical metadata);
- `jobAd.sections.companyDescription.text`;
- `jobAd.sections.jobDescription.text`;
- `jobAd.sections.qualifications.text`;
- `jobAd.sections.additionalInformation.text`;
- tolerate the documented legacy equivalent section shape through explicit fixture-tested
  aliases, not recursive “find any text” parsing.

Unknown fields are ignored for forward compatibility. Required fields and types fail closed.
No candidate/application objects are requested.

## Mapping to JobPilot

| `RawJob` field | SmartRecruiters mapping |
|---|---|
| `source` | literal `smartrecruiters` |
| `externalId` | required detail `id`; preserve exact case/text |
| `url` | validated/canonicalized `postingUrl` |
| `title` | `name` |
| `company` | detail `company.name`, falling back to verified company identifier only if absent |
| `location` | ordered nonblank join of city, region, uppercase country; append `Remote` when true |
| `description` | deterministic metadata lines for department/function, then plain-text job description, qualifications, and additional information; omit company boilerplate |
| `employmentType` | employment-type label, then ID |
| `publishedAt` | strict ISO-8601 `releasedDate`; null only when absent, malformed value is schema error when present |
| `deadline` | null; Posting API does not reliably expose it |
| `rawPayload` | bounded serialized public detail DTO only; never headers or transport metadata |
| `providerTenant` | exact configured company identifier |
| `locationData.workplaceType` | `Remote` when `location.remote=true`, otherwise null/unknown |
| `locationData.structuredLocations` | one normalized city/region/country string |
| `locationData.remoteRegions` | empty; remote region must be proved by vacancy content/screening |
| `careerData.providerSeniority` | experience-level label, then ID |
| `careerData` years/mandatory | null unless a future documented field supplies them; do not infer years from label |

Prepend nonblank provider labels in this fixed order as `Department: <label>` and
`Function: <label>`, then the vacancy sections. Omit absent labels and exact normalized
duplicates. Company description remains in the bounded provider DTO/raw payload but is not
copied into `description`, avoiding repeated employer boilerplate. No `RawJob` shape change
is required.

### Description extraction

HTML-unescape each allowed section, remove tags, collapse Unicode whitespace, drop empty
sections, de-duplicate identical normalized sections, and join with one newline. Reuse the
same conservative approach as existing adapters. Do not execute markup, load images, follow
embedded links, or log the text.

### URL canonicalization

Accept only an absolute HTTPS `postingUrl` with a host and no userinfo. Normalize scheme and
host case, remove fragment, remove only a fixed fixture-tested set of provider tracking
parameters, and preserve unknown query parameters because they may be functional. Reject a
missing or unsafe URL instead of constructing/guessing one. `applyUrl` is never preferred as
the vacancy identity URL.

## Duplicate and identity rules

- Tenant identity is `(smartrecruiters, companyIdentifier)`.
- Job identity is `(smartrecruiters, companyIdentifier, posting.id)`; do not prefix the ID
  with company because persistence already scopes it by tenant.
- Preserve company identifier case. Do not lowercase or trim accepted values silently.
- Duplicate list entries across pages/partitions collapse before detail fetching.
- Same ID with conflicting company identifier, title, or UUID fails the tenant as a schema
  inconsistency.
- Duplicate detail IDs fail before returning results.

## Health and failure mapping

All requests for a company run inside one monitor supplier. Exactly one final attempt is
recorded per configured company per ingestion run:

- zero valid public postings -> `EMPTY_SUCCESS`;
- complete non-empty union -> `SUCCESS`, fetched count = unique hydrated details;
- any page/detail/validation failure -> `FAILURE`, fetched count 0.

Existing mappings apply:

| Condition | Category |
|---|---|
| 404 or 410 list endpoint | `INVALID_TENANT` |
| 401 or 403 | `AUTHORIZATION_ERROR` (also signals public contract regression) |
| 429 | `RATE_LIMITED` |
| other 4xx | `CLIENT_ERROR` |
| 5xx | `SERVER_ERROR` |
| timeout | `TIMEOUT` |
| DNS/TLS/connect/I/O | `NETWORK_ERROR` |
| any response above 10 MiB | `RESPONSE_TOO_LARGE` |
| malformed JSON, wrong type, missing required field, identity conflict, repeated page, impossible pagination | `RESPONSE_PARSE_ERROR` |
| rejected company identifier/destination | `CONFIGURATION_ERROR` |

Aggregate page/job cap exhaustion should use a small new provider-local structured exception
that the classifier maps to `RESPONSE_TOO_LARGE`; do not infer this from message text and do
not raise the global byte limit. If changing the closed classifier mapping would require a
new category, prefer the existing `RESPONSE_TOO_LARGE` category and document “aggregate job
cap” in fixed bounded safe text.

Run ID correlation, attempt history, current roll-up, failure isolation, and best-effort
health persistence remain unchanged.

### Safe logging

Log only run ID, fixed provider name, validated company identifier, final status, fetched
count, duration, fixed failure category, HTTP status, consecutive-failure count, configured
caps, and bounded fixed error text. Never log full URLs, query strings, posting IDs, titles,
locations, descriptions, response bodies, page bodies, headers, cookies, tokens, ref/apply
URLs, or DTO serialization.

## Configuration design

Proposed documentation-only shape:

| Layer | Name |
|---|---|
| YAML | `jobpilot.sources.smartrecruiters-company-identifiers` |
| Java | `JobPilotProperties.Sources.smartrecruitersCompanyIdentifiers` |
| Environment | `SMARTRECRUITERS_COMPANY_IDENTIFIERS` |
| Format | comma-separated Spring list, matching the four existing provider registries |

Validation:

- each value must match `[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}` (1–63 ASCII characters);
- values are already canonical path identifiers copied from official evidence; preserve case;
- reject null, blank, leading/trailing whitespace, slash, percent escape, query/fragment,
  control character, or grammar violation;
- reject exact duplicates; do not silently de-duplicate;
- maximum **100 configured SmartRecruiters companies**;
- preserve declaration order for deterministic attempts; registry validation pins order and
  exact expected additions;
- base/production default is empty (`${SMARTRECRUITERS_COMPANY_IDENTIFIERS:}`), so production
  is explicit opt-in;
- Phase 3.3C development default is also empty, keeping implementation and automated tests
  network-dormant;
- the proposed Phase 3.3D initial development registry is the deterministic sequence
  `Endava,Ubisoft2,Gameloft` after separately authorized live validation; `BoschGroup` and
  `AECOM2` remain explicit later opt-ins because their global boards and remote queries are
  much larger.

The list contains identifiers only. Do not add host names, arbitrary URLs, API keys, country
filters, or employer-specific flags to an entry.

## Offline unit and fixture plan

Create the smallest synthetic/sanitized JSON needed to prove each branch. Fixtures must use
invented company/job names and IDs except where a public contract field shape cannot be
demonstrated otherwise. Remove personal names, email addresses, tracking parameters,
unneeded internal IDs, and long boilerplate/description text.

Required cases:

1. successful one-page country response plus one detail;
2. successful multi-page response with correct offsets/total;
3. country + remote partitions with a duplicate posting and one detail request;
4. empty board/partitions;
5. malformed JSON;
6. wrong root/field type;
7. missing posting ID, title, URL, or detail identity;
8. duplicate across pages;
9. conflicting duplicate identity;
10. repeated page fingerprint/repeated offset;
11. inconsistent or changing total;
12. page cap and unique-job cap;
13. response-too-large (declared and streamed/chunked);
14. 429 with bounded Retry-After;
15. retryable 5xx then success and exhausted 5xx;
16. timeout/network failure;
17. 404/410 tenant not found;
18. 401/403 public-access regression;
19. representative Bucharest/Romania structured location;
20. Europe-remote content that screening accepts;
21. remote-flagged but country-incompatible content that screening rejects;
22. HTML description sanitization, timestamp parsing, department/function, employment and
    experience mapping;
23. unsafe/malformed posting URL and tracking canonicalization;
24. exact tenant-attempt count and all-or-nothing later-detail failure.

Transport tests must assert exact SmartRecruiters host acceptance, sibling/subdomain/IP/user
info/non-default-port rejection, redirect confinement, public-address screening, content type,
size bound, retry count, and absence of secret headers.

## Integration and registry-validation plan

- Extend the current ATS monitoring parameterized test to SmartRecruiters and prove one
  `TenantFetchMonitor` result for multi-page + detail work.
- Run a source in the ingestion service with one healthy and one failing synthetic tenant;
  prove failure isolation, run ID correlation, unique raw count, and no partial jobs from the
  failed tenant.
- Prove persistence identity for the same posting ID under two SmartRecruiters companies.
- Prove `SourceRegistryValidationTest` binds the real configuration path, enforces grammar,
  no whitespace/duplicates, deterministic order, maximum count, and exact evidence-backed
  Phase 3.3D additions.
- Keep all HTTP tests local/stubbed. `./mvnw test` and `./mvnw verify` must make **zero public
  ATS requests**.
- No migration is expected: provider/source names and tenant-aware job identity already
  support a new source string.

## Documentation changes for implementation

Phase 3.3C should update README provider/configuration tables, source-health provider lists,
architecture notes where they say “four adapters,” and the roadmap implementation status.
Phase 3.3D separately records official board evidence, registry additions, and live results.

## Phase 3.3D live-validation plan

Only after Phase 3.3C is committed, reviewed, and tests are green:

1. verify an exact clean checkpoint and runtime health;
2. add a small evidence-backed initial registry, preferably the lower-request-volume boards
   `Endava`, `Ubisoft2`, and `Gameloft`; treat Bosch/AECOM request volume as an explicit
   operator decision after dry contract checks;
3. restart/rebuild only with separate authorization and standard runbook;
4. start one ingestion, never overlapping another;
5. require one attempt per configured SmartRecruiters company and inspect only safe
   aggregates/health;
6. verify Romania/Bucharest discovery, duplicate reconciliation, screening dispositions,
   response sizes, request duration, and no rejected job with a retained score;
7. expand to Bosch/AECOM only if the 500-job union and provider rate behavior stay bounded.

## Rollback

- Configuration rollback: remove only the new SmartRecruiters registry values and restart
  through the normal authorized deployment path. Historical jobs/attempts remain evidence.
- Code rollback: revert the unpublished Phase 3.3C change through a normal forward Git
  change; no database down migration is needed because none is planned.
- Operational stop: disable new entries by emptying the dedicated environment variable;
  never delete historical jobs or health rows as a rollback mechanism.
- Public-access regression (401/403/CAPTCHA/JS requirement): stop the source, retain health
  evidence, and reassess. Do not add credentials or browser fallback.

## Explicitly out of scope

- Editing the registry or any runtime setting in Phase 3.3B/3.3C design work;
- employer-specific adapter branches or URLs;
- full global-board ingestion beyond the two bounded discovery partitions;
- claiming every Europe-remote role is discoverable or Romania-eligible;
- authentication, API keys, application submission, candidate data, cookies, browser
  rendering, CAPTCHA handling, proxies, access-control bypass, tenant discovery/enumeration,
  generic career-site scraping, or LinkedIn/aggregator scraping;
- raising the 10 MiB response limit;
- changing `RawJob`, screening, scoring, reconciliation, notifications, digests, migrations,
  or the browser worker.
