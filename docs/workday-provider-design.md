# Workday provider — reconnaissance and implementation design (Phase 4B.2A)

Reconnaissance of six candidate Workday career sites against the public, unauthenticated
Workday CXS job-search API, and the resulting adapter design. **This phase produced no
implementation code, no migration, and no configuration change.**

All evidence below was captured on 2026-08-04 with `curl` only. No browser automation, no
authentication, no bot-protection bypass, and no session state were used. Every request was
an ordinary anonymous call to the same endpoint the public career page itself calls.

- **Aggregators are out of scope.** Overzeros was used only to discover employer career
  sites and is not a source. Polymarket and Kpler are deferred.
- **`robots.txt` compliance:** every probed host allows the career-site paths and disallows
  only `/refreshFacet/`. The design below never calls `/refreshFacet/`.

---

## 1. Endpoint model

Workday exposes one JSON search endpoint and one JSON detail endpoint per career site:

```
POST https://{tenant}.{shard}.myworkdayjobs.com/wday/cxs/{tenant}/{careerSite}/jobs
GET  https://{tenant}.{shard}.myworkdayjobs.com/wday/cxs/{tenant}/{careerSite}{externalPath}
```

The `{tenant}` path segment equals the first label of the hostname on **all six** candidate
sites, so the host can be derived from `tenant` + `shard`. This was verified individually,
not assumed.

### Search request

```jsonc
// POST .../wday/cxs/db/DBWebsite/jobs
// Content-Type: application/json
{
  "appliedFacets": {},          // or {"<countryFacetParam>": ["<countryId>"]}
  "limit": 20,                  // hard maximum, see §4
  "offset": 0,
  "searchText": ""
}
```

### Search response envelope (identical on all six sites)

```jsonc
{
  "total": 1055,                // populated on offset=0 only — see §4
  "jobPostings": [ /* … */ ],
  "facets": [ /* … */ ],
  "userAuthenticated": false    // confirms no session is required
}
```

### Search summary item

```jsonc
{
  "title": "Senior Experte Firmenkunden (d/m/w) – Südostbayern",
  "externalPath": "/job/Regensburg-Maximilianstrae-9/Senior-Experte-…_R0381827",
  "locationsText": "Regensburg Maximilianstraße 9",   // ABSENT on 2 of 6 sites
  "postedOn": "Posted Today",                          // relative text, never a date
  "bulletFields": ["R0381827"]                         // tenant-configured, NOT a schema
}
```

### Detail response

```jsonc
{
  "jobPostingInfo": {
    "id": "86c3288815551001dbca4fea79490000",   // stable Workday GUID
    "title": "Vendor Performance Management - VPM - PMO (f/m/x)",
    "jobDescription": "<span>…</span>",          // HTML
    "location": "Bucharest, 6A Dimitrie Pompeiu Blvd",
    "additionalLocations": ["Bucharest, Romania"],  // present only when multi-location
    "startDate": "2026-08-04",                   // real ISO date
    "postedOn": "Posted Today",
    "timeType": "Full time",
    "jobReqId": "R0441509",
    "country": { "descriptor": "Romania", "id": "f2e609fe92974a55a05fc1cdc2852122" },
    "externalUrl": "https://db.wd3.myworkdayjobs.com/DBWebsite/job/…",
    "canApply": true, "posted": true
  },
  "hiringOrganization": { "name": "F063 DB Global Technology SRL", "url": "" },
  "similarJobs": [ /* … */ ],
  "userAuthenticated": false
}
```

**No cookies, CSRF tokens, JavaScript execution, authentication, or session state are
required.** `userAuthenticated: false` is returned on every response, search and detail.

---

## 2. Per-site endpoint evidence

Measured with `limit=20, offset=0`. "RO" = postings matching the Romania country facet.

| # | Host | Career site | CXS tenant | Total | RO | Search bytes | RO-filtered bytes | Detail bytes | Latency |
|---|---|---|---|---|---|---|---|---|---|
| 1 | `db.wd3.myworkdayjobs.com` | `DBWebsite` | `db` | 1055 | **127** | 15 598 | 9 377 | 8 718 | 1.0–2.2 s |
| 2 | `lseg.wd3.myworkdayjobs.com` | `Careers` | `lseg` | 773 | **65** | 17 146 | 9 402 | 14 361 | 2.1 s |
| 3 | `lseg.wd3.myworkdayjobs.com` | `Graduate_Careers` | `lseg` | **1** | **0** | 904 | — | — | 0.9 s |
| 4 | `nxp.wd3.myworkdayjobs.com` | `careers` | `nxp` | 803 | **57** | 15 165 | 8 578 | 7 919 | 1.9 s |
| 5 | `alliancewd.wd3.myworkdayjobs.com` | `renault-group-careers` | `alliancewd` | 228 | **2** | 13 084 | 2 190 | 5 865 | 1.7 s |
| 6 | `accenture.wd103.myworkdayjobs.com` | `AccentureCareers` | `accenture` | 2000 † | **107** | 73 155 | 18 488 | 10 597 | **5.6 s** |

† Accenture's `total` is capped at exactly 2000; `offset=1990` returned 10 items. The cap is
irrelevant under the Romania filter (107 ≪ 2000) but must not be mistaken for a true count.

All six returned `HTTP 200`, `Content-Type: application/json`, `userAuthenticated: false`.

### Field availability per site

| Field | db | lseg/Careers | lseg/Grad | nxp | alliancewd | accenture |
|---|---|---|---|---|---|---|
| `title` (search) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `externalPath` (search) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `locationsText` (search) | ✅ | ⚠️ "2 Locations" | ⚠️ | ✅ | ❌ **absent** | ❌ **absent** |
| `postedOn` (search) | relative | relative | relative | relative | relative | relative |
| `bulletFields` semantics | `[reqId]` | `[reqId, division]` | `[reqId, division]` | `[reqId]` | `[location, family]` | `[reqId, note]` |
| Detail `id` / `title` / `jobDescription` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Detail `startDate` (ISO) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Detail `country` (structured) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Detail `externalUrl` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Detail `additionalLocations` | — | ✅ | ✅ | — | — | — |
| Detail `endDate` / `timeLeftToApply` | — | — | — | — | ✅ | — |
| **Explicit remote/hybrid field** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

### Country facet — the key to bounded fetching

Workday uses **global reference GUIDs for countries that are identical across tenants**.
Romania is `f2e609fe92974a55a05fc1cdc2852122` on db, lseg, nxp, alliancewd *and* accenture.
(Cross-checked: India `c4f78be1…` matches on db and nxp; Brazil `1a29bb13…` on lseg and
alliancewd.) A single constant therefore works for every tenant.

**However, the facet parameter name is tenant-specific and must be discovered:**

| Site | Country facet parameter | Shape |
|---|---|---|
| db/DBWebsite | `Country` | top-level, flat |
| nxp/careers | `Location_Country` | top-level, flat |
| lseg/Careers | `locationCountry` | **nested** inside `locationMainGroup` |
| lseg/Graduate_Careers | `locationCountry` | nested |
| alliancewd/renault-group-careers | `locationCountry` | nested |
| accenture/AccentureCareers | `locationCountry` | nested |

Filtering was verified end to end on all five non-empty sites:

```jsonc
// POST .../wday/cxs/db/DBWebsite/jobs   → total 127 (was 1055)
{"appliedFacets":{"Country":["f2e609fe92974a55a05fc1cdc2852122"]},
 "limit":20,"offset":0,"searchText":""}

// POST .../wday/cxs/accenture/AccentureCareers/jobs   → total 107 (was 2000)
{"appliedFacets":{"locationCountry":["f2e609fe92974a55a05fc1cdc2852122"]},
 "limit":20,"offset":0,"searchText":""}
```

---

## 3. Compatibility matrix — can one generic adapter serve all six?

| Concern | Uniform? | Notes |
|---|---|---|
| URL construction | ✅ | `{tenant}.{shard}.myworkdayjobs.com` + `/wday/cxs/{tenant}/{site}` on all six |
| HTTP method / content type | ✅ | POST JSON search, GET JSON detail |
| Auth / cookies / CSRF / JS | ✅ | None required anywhere |
| Response envelope | ✅ | `total` / `jobPostings` / `facets` / `userAuthenticated` |
| Pagination model | ✅ | `offset` + `limit`, max 20 |
| Detail schema core | ✅ | 17-field core identical on all six |
| Country facet **value** | ✅ | One global Romania GUID |
| Country facet **parameter name** | ❌ | 3 distinct names, 2 distinct nesting shapes → **runtime discovery** |
| `locationsText` in search | ❌ | Absent on alliancewd and accenture; `"N Locations"` on lseg |
| `bulletFields` meaning | ❌ | Tenant-configured display array — **must not be parsed** |
| Posted date in search | ❌ | Relative text on all six → detail required for a real date |
| Remote/hybrid flag | ❌ | Absent everywhere → must be inferred from text (existing pipeline already does) |

**Verdict: one generic adapter is feasible.** Every divergence is absorbed by two rules:
1. discover the country facet parameter from the unfiltered bootstrap response by locating
   the facet (top-level or nested one level) whose values contain the Romania GUID;
2. treat the search summary as *identity + title only*, and take every other field from detail.

No tenant-specific subclass, no per-tenant branch, and no hand-maintained facet map is needed.

---

## 4. Pagination, limits, and failure semantics

- **`limit` maximum is 20.** `limit=21`, `25`, `50`, `100`, `200` all return
  `HTTP 400 {"errorCode":"HTTP_400", …}`. This is a hard ceiling, not a soft cap.
- **`offset` pagination is correct.** db Romania (127): `offset=0` → 20, `offset=20` → 20,
  `offset=120` → 7. Sum 127. ✅
- **`total` is populated on `offset=0` only**; later pages return `total: 0`. The adapter
  must capture the count from the first page and never re-read it.
- **Result-set cap:** Accenture caps `total` at 2000. Irrelevant under the Romania filter.
- Error bodies are small JSON with an `errorCode`; they carry no secrets.

---

## 5. Fetch-cost model

Per six-hour ingestion cycle, with the Romania country facet applied.

| Site | RO postings | Search pages (⌈n/20⌉) | Bootstrap | Detail requests |
|---|---|---|---|---|
| db/DBWebsite | 127 | 7 | 1 | ≤127 |
| lseg/Careers | 65 | 4 | 1 | ≤65 |
| lseg/Graduate_Careers | 0 | 1 | 1 | 0 |
| nxp/careers | 57 | 3 | 1 | ≤57 |
| alliancewd/renault-group-careers | 2 | 1 | 1 | ≤2 |
| accenture/AccentureCareers | 107 | 6 | 1 | ≤107 |
| **All six** | **358** | **22** | **6** | **≤358** |
| **Recommended three (db, nxp, lseg/Careers)** | **249** | **14** | **3** | **≤249** |

**Worst case, all six:** 28 search + 358 detail = **386 requests**, ≈ 560 KB of search
payload + ≈ 5.4 MB of detail payload ≈ **6 MB per cycle**.
**Recommended three:** 17 search + 249 detail = **266 requests**, ≈ 3.5 MB.

Largest single response measured: **73 155 bytes** (Accenture unfiltered, dominated by a
627-value facet tree) — 0.7 % of the existing 10 MiB `JOBPILOT_HTTP_MAX_RESPONSE_BYTES`
ceiling. **No response-size change is required.**

**Timing.** SmartRecruiters is the existing precedent for detail-per-posting fetching: 690
postings in 111.4 s ≈ 0.16 s/posting with connection reuse. Extrapolating, all six Workday
sites add ≈ 60–90 s of detail plus ≈ 40 s of search — roughly **2 minutes**, taking the whole
cycle from ~2.6 min to ~5 min. The recommended three-site set adds ≈ 70 s.

**Concurrency: none.** Keep the existing strictly sequential per-tenant model. The measured
worst-case latency is comfortable and parallelism would add rate-limit and fairness risk for
no scheduling benefit at a six-hour cadence.

**Timeouts.** Slowest observed request is 5.6 s (Accenture unfiltered bootstrap), inside the
existing 20 s `jobpilot.http.response-timeout`. No timeout change is required — but see the
open risk in §9 regarding that same 20 s value and Telegram.

---

## 6. Proposed configuration shape

A single company identifier is **not** sufficient: `lseg` alone hosts two distinct career
sites, and the shard differs (`wd3` vs `wd103`). The configuration must carry
**tenant + shard + career site**.

```
WORKDAY_CAREER_SITES=db:wd3:DBWebsite,nxp:wd3:careers,lseg:wd3:Careers
```

Each entry is `tenant:shard:careerSite`, from which the adapter derives:

- host — `{tenant}.{shard}.myworkdayjobs.com`
- search — `https://{host}/wday/cxs/{tenant}/{careerSite}/jobs`
- detail — `https://{host}/wday/cxs/{tenant}/{careerSite}{externalPath}`

Binding into the existing `JobPilotProperties.Sources` record as
`List<String> workdayCareerSites`, validated in the compact constructor exactly like the
other five providers:

- each entry matches `^[a-zA-Z0-9][a-zA-Z0-9._-]{0,62}:[a-z0-9]{2,10}:[A-Za-z0-9._-]{1,64}$`;
- duplicates rejected (as SmartRecruiters already does);
- bounded to a maximum of 25 configured sites;
- empty by default, so the provider is inert until explicitly configured.

`application.yml` gains `workday-career-sites: ${WORKDAY_CAREER_SITES:}` and
`docker-compose.yml` a matching passthrough. `.env.example` documents placeholders only.

---

## 7. Ingestion strategy — minimising detail requests

Ordered, and deliberately conservative about correctness:

1. **Bootstrap (1 request/site).** Unfiltered `limit=1` search purely to read `facets`;
   locate the country facet parameter by searching top-level facets and one nesting level
   for the Romania GUID. Cache for the cycle. If not found → skip the country filter and
   fall back to bounded unfiltered paging (never silently return nothing).
2. **Server-side Romania filter.** Apply `{"<param>":["f2e609fe…"]}`. This is where the
   real saving is: 4859 postings → 358, a **13× reduction**, done by Workday rather than by us.
3. **Page the filtered set** at `limit=20`, capped at `MAX_UNIQUE_JOBS_PER_SITE = 300`
   (the binding bound on one career site's contribution) and `MAX_LIST_PAGES_PER_SITE = 20`
   (a separate safety net for pathological paging). The two are deliberately not aligned:
   20 x 20 = 400 keeps the 300-posting cap reachable rather than dead. A per-site runtime
   deadline and a detail-request cap bound the remaining two dimensions.
4. **Conservative title prefilter before detail.** Skip the detail request only when the
   title carries an unambiguous senior marker (`Senior`, `Lead`, `Principal`, `Staff`,
   `Head of`, `Director`, `VP`, `Chief`) **and** no early-career marker (`Intern`,
   `Graduate`, `Junior`, `Trainee`, `Student`, `Entry`, `Apprentice`, `Academy`).
   Titles that are merely generic ("Software Engineer") are **never** skipped — they go to
   detail, because seniority frequently lives only in the description.
5. **Fetch detail** for everything that survives, and build `RawJob` from detail fields.
6. **Hand off unchanged** to the existing screening pipeline. No screening, scoring, or
   eligibility logic is modified by this provider.

**Correctness guard.** Because the Romania facet matches *any* location on a posting, a
Poland-primary job with `additionalLocations: ["Bucharest, Romania"]` is legitimately
returned (observed on lseg). The adapter must therefore populate `RawLocationData` from
`location` **and** `additionalLocations` **and** `country.descriptor` together — using only
the primary `country` field would wrongly discard genuine Bucharest roles. This is the single
most important correctness rule in the design.

---

## 8. Implementation plan

### New classes — `com.jobpilot.sources.workday`

| Class | Responsibility |
|---|---|
| `WorkdayJobSource` | `implements JobSource`; per-site loop, bootstrap, paging, detail, `RawJob` mapping |
| `WorkdayCareerSite` | Record `(tenant, shard, careerSite)` + `host()`, `searchUrl()`, `detailUrl(path)`, `tenantKey()`; parses and validates one config entry |
| `WorkdayFacetResolver` | Discovers the country facet parameter from a bootstrap response; holds the Romania GUID constant |
| `WorkdayLimitException` | Mirrors `SmartRecruitersLimitException` for cap breaches |

### Reused without modification

`JobSource`, `ExternalHttpClient.postJson` / `getJson`, `TenantFetchMonitor.fetch`,
`TenantFailureClassifier`, `SourceTenantHealthRecorder`, `IngestionRunContext`,
`UrlCanonicalizer`, `Hashing`, `RawJob`, `RawLocationData`, `RawCareerData`,
`JobRelevanceFilter`, `LocationEligibilityService`, `EarlyCareerEligibilityService`,
`JobProcessor`, `JobIngestionService`. Spring discovers the new `@Component` through the
existing `List<JobSource>` injection — **no wiring change**.

### One required change outside the new package

`ExternalHttpClient.family()` is a strict host allowlist and currently rejects Workday. Add a
`WORKDAY` `DestinationFamily` matching `*.myworkdayjobs.com` with a validated tenant/shard
label, following the existing `.recruitee.com` wildcard precedent. Without this every request
fails `INVALID_DESTINATION`. This is a contained, testable addition — not a defect fix.

### Provider naming and identity

- `source` = `workday`
- `provider_tenant` = `{tenant}/{careerSite}` (e.g. `db/DBWebsite`) — **required**, because
  `lseg` runs two career sites that must occupy separate health rows
- `external_id` = `jobPostingInfo.id`, the 32-character Workday GUID; fall back to
  `externalPath` if absent. `bulletFields` is **never** parsed for identity — it is a
  tenant-configured display array whose meaning differs per site.
- Deduplication uses the existing `jobs_source_tenant_external_uk` unique key on
  `(source, provider_tenant, external_id)`. No new identity concept.
- `canonical_url` = `jobPostingInfo.externalUrl`, passed through `UrlCanonicalizer`.

### Health and error categories

`TenantFetchMonitor.fetch("workday", "db/DBWebsite", …)` wraps each site, so per-tenant
attempts, `source_tenant_fetch_logs` rows, and `source_tenant_health` rows all work with no
change. Existing categories cover every observed failure: `CLIENT_ERROR` (the HTTP 400 from
an oversized `limit`), `RESPONSE_PARSE_ERROR`, `RESPONSE_TOO_LARGE`, `TIMEOUT`,
`NETWORK_ERROR`, `SERVER_ERROR`, `INVALID_TENANT` (404 for a wrong tenant/site pair).
**No new failure category is required.**

### Tests (all offline, fixture-driven)

`WorkdayJobSourceTest`, `WorkdayCareerSiteTest`, `WorkdayFacetResolverTest`,
plus `SourceRegistryValidationTest` and `ExternalHttpClientTest` extensions. Fixtures under
`src/test/resources/fixtures/workday/`: a search page, a Romania-filtered page, a last
partial page, all four facet shapes, a multi-location detail with `additionalLocations`, an
`HTTP 400` limit error, and a malformed payload. **No test may contact
`*.myworkdayjobs.com`** — enforced the same way the existing ATS tests are.

### Documentation

README source list, the environment-variable table, `.env.example` placeholders, and the
roadmap Phase 4B.2 entry.

### Database migration

**None required, and none should be created.** `jobs.source` is `VARCHAR(100)` with no CHECK
constraint; `provider_tenant` is `VARCHAR(300)` (longest key `alliancewd/renault-group-careers`
= 33); `external_id` is `VARCHAR(255)` (GUID = 32); `canonical_url` is `VARCHAR(2000)`
(observed ≈ 150). The unique key already has the required shape. Schema stays at **V12**.

---

## 9. Risks and unresolved questions

1. **Facet-name discovery is the single point of fragility.** If a tenant renames or removes
   its country facet, the adapter falls back to unfiltered paging and the cost model changes
   by an order of magnitude. Mitigation: the page/job caps in §7 bound the damage, and the
   fallback must emit a distinct log signal.
2. **Relative `postedOn` in search.** Freshness scoring depends on a real date, which only
   detail provides — this is structural, and it is why detail cannot be skipped for
   candidates we intend to persist.
3. **No remote/hybrid field anywhere.** Remote detection falls entirely to the existing
   text-based `LocationEligibilityService`. Workday adds no signal here.
4. **Accenture is the expensive outlier** — 5.6 s bootstrap, 73 KB facet tree, 18.5 KB
   filtered pages, 107 Romania postings — for the weakest role fit (see §10).
5. **The title prefilter can theoretically drop an eligible role** whose title says "Senior"
   but whose body describes a graduate track. Judged acceptable: the rule requires an
   unambiguous senior marker *and* the absence of any early-career marker.
6. **Unresolved — sampling depth.** Role-fit judgements in §10 rest on the first 20 Romania
   postings per site plus keyword probes, not the full 358. A controlled validation run
   (Phase 4B.2B) is required before any tenant is made permanent.
7. **Unresolved — posting churn.** Whether Workday `externalPath` values are stable across
   re-postings was not measured; the GUID `id` is used precisely to avoid depending on it.
   One observed posting had a slug whose title no longer matched the detail title, so slugs
   are demonstrably not authoritative.
8. **Out of scope but blocking-adjacent:** the pre-existing 20 s
   `jobpilot.http.response-timeout` vs 25 s Telegram long-poll mismatch (§ Pre-flight) is
   still unfixed in `main`. Workday's 5.6 s worst case is unaffected, but the two share the
   same setting, so any future change to it must consider both.

---

## 10. Role-fit assessment and recommended validation tenants

Evidence: first 20 Romania postings per site (recency-ordered) plus Romania-scoped
`searchText` probes for `internship`, `graduate`, and `junior`. `searchText` also matches
description text, so totals overstate; the **titles** are the signal.

| Site | RO | Early-career software evidence | Verdict |
|---|---|---|---|
| **db/DBWebsite** | 127 | `Code First Girls Programme – Junior Java Developer`, `… Junior Python Developer`, `… Junior Mobile Developer`; 16 junior hits; hiring entity `DB Global Technology SRL`, Bucharest | **VALIDATE** |
| **nxp/careers** | 57 | `Lab Engineer Intern`, `System Engineer Intern`, `Junior SW Safety Engineer - Student`, `Junior Embedded Crypto Software Developer`; Bucharest + Sibiu; 19/20 sampled titles technical | **VALIDATE** |
| **lseg/Careers** | 65 | `Junior Full Stack Developer – Issuer Services`; dense Java / C++ / .NET / full-stack in Bucharest; 15/20 sampled titles technical | **VALIDATE** |
| accenture/AccentureCareers | 107 | 10 junior hits but all non-software (`Junior Procurement Support Analyst with German`); sample dominated by HR Generalist / consulting; 7/20 technical and those are SAP/Teamcenter | **DEFER** — highest cost, weakest fit |
| alliancewd/renault-group-careers | 2 | Both are internships (`[Internship] Product Manager Junior LCV`, Bucuresti) but neither is software | **DEFER** — negligible yield |
| lseg/Graduate_Careers | 0 | Entire site holds 1 posting, in Bangalore | **REJECT** — effectively dead |

**Recommended initial validation set (Phase 4B.2B):**

```
WORKDAY_CAREER_SITES=db:wd3:DBWebsite,nxp:wd3:careers,lseg:wd3:Careers
```

249 Romania postings, 17 search + ≤249 detail requests, ≈ 70 s added per cycle — the three
sites carrying demonstrable early-career Java/backend/embedded roles in Bucharest.

---

## 11. Decision

### GO — generic Workday support is approved for implementation.

Justification:

- One code path serves all six sites; the only real divergence (country facet parameter name)
  is solved by runtime discovery rather than a per-tenant map.
- The API is public, anonymous, stable in shape, and `robots.txt`-compliant. No cookies,
  CSRF, JavaScript, authentication, or session state.
- Cost is bounded and modest: 266 requests / ≈ 3.5 MB / ≈ 70 s per cycle for the recommended
  three sites, against an existing 10 MiB response ceiling and a 6-hour cadence.
- It requires **no database migration**, **no new dependency**, **no new failure category**,
  and **no change to screening or scoring**. The only change outside the new package is one
  host-allowlist entry in `ExternalHttpClient`.
- It delivers what the phase set out to find: real Bucharest early-career software vacancies
  — concretely, a Junior Java Developer programme at Deutsche Bank Bucharest.

**Scoped NO-GO:** `lseg:wd3:Graduate_Careers` must not be configured (1 posting, wrong
country). `accenture` and `alliancewd` are deferred pending the 4B.2B outcome, not rejected.

**Gate:** implementation lands behind empty-by-default configuration; the three recommended
sites are activated only after one controlled validation run confirms per-tenant yield.
