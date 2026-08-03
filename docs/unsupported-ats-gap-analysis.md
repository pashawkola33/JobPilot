# Phase 3.3B unsupported ATS gap analysis

Research snapshot: **2026-08-03**. This is a documentation-only provider-selection
exercise. It did not change source configuration, the tenant registry, Java code, the
database, or the running application.

## Decision

Select **SmartRecruiters** for Phase 3.3C.

Five relevant employers were confirmed from their official careers flows: **Bosch Group,
Ubisoft, Endava, Gameloft, and AECOM**. Their exact public boards returned HTTP 200 without
cookies, credentials, or JavaScript. The documented public Posting API has offset
pagination, a 100-item maximum page, `totalFound`, stable posting IDs, country filtering,
and separate complete job details. It is the best combination of current Romania value,
early-career paths, bounded public access, data completeness, and maintainability.

The five boards contained **10,095 global postings and 242 exact `country=ro` postings** at
the snapshot. Of the Romania postings, **112 named Bucharest**, **12 had explicit
intern/graduate/junior/working-student/trainee terms in the title**, and **128 matched a
broad profile-title vocabulary** (software/developer/engineer/QA/support/security/Java and
related terms). These are discovery upper bounds, not predicted MATCH/REVIEW counts. A
separate `q=remote` sample found seven remote-flagged postings with a European country code;
none is claimed Romania-eligible without full vacancy screening.

## Method and evidence rules

- The starting set was every `DEFER_UNSUPPORTED_ATS` and `HOLD_AMBIGUOUS` employer in
  [Phase 3.3A](source-expansion-audit.md), including the four companies compressed into its
  GE Vernova/AECOM/HARMAN/Schneider Electric row.
- The set was conservatively expanded to **24 employers**, below the 50-employer ceiling,
  to cover Romanian-founded technology, multinationals with Romanian engineering teams,
  cybersecurity, fintech/payments, enterprise software, consulting/integration, and
  Europe-oriented remote work.
- A provider or identifier was accepted only from an official company page, an official
  redirect/link, or provider configuration in that page. Search snippets and aggregators
  were not sole evidence.
- Only exact officially linked boards were sampled. No tenant variants were tried and no
  tenant namespace was enumerated.
- Small public requests established contracts and aggregates. No raw ATS response, cookie,
  token, header, credential, or personal data was saved.

## Employer audit

Counts are point-in-time official-board observations. `~` means a bounded query or visible
page estimate rather than a complete exact total. “Not established” means the evidence did
not justify another request. Remote evidence never means Romania eligibility by itself.

| Company | Official careers URL | Provider | Confidence | Tenant/board ID | Bucharest/Romania evidence | EU remote evidence | Entry-level evidence | Current board size | Decision |
|---|---|---|---|---|---|---|---|---:|---|
| Bosch Group | [Bosch Romania careers](https://www.bosch.ro/cariere/) | SmartRecruiters | CONFIRMED | `BoschGroup` | 61 Romania; current Romanian working-student/intern routes | 6 Europe-coded remote flags in `q=remote` sample; eligibility unproven | 12 title-explicit early-career roles | HTTP 200; 4,717 global | TARGET_PROVIDER_EMPLOYER |
| Ubisoft | [Ubisoft careers](https://www.ubisoft.com/en-us/company/careers) | SmartRecruiters | CONFIRMED | `Ubisoft2` | 3 Romania, 2 Bucharest; official Bucharest studio | 1 Europe-coded remote flag; eligibility unproven | Official student/graduate guidance; 0 title-explicit in current RO set | HTTP 200; 277 global | TARGET_PROVIDER_EMPLOYER |
| Endava | [Endava jobs](https://www.endava.com/careers/jobs) | SmartRecruiters | CONFIRMED | `Endava` | 97 Romania, 29 Bucharest | No Europe-coded remote result established | 0 title-explicit in current RO set; broad QA/support/engineering routes | HTTP 200; 214 global | TARGET_PROVIDER_EMPLOYER |
| Gameloft | [Gameloft jobs](https://www.gameloft.com/jobs) | SmartRecruiters | CONFIRMED | `Gameloft` | 5 Romania, all 5 Bucharest | None observed | Official Bucharest studio describes recurring technical internships; current programme closed | HTTP 200; 58 global | TARGET_PROVIDER_EMPLOYER |
| AECOM | [AECOM careers](https://aecom.com/careers/) | SmartRecruiters | CONFIRMED | `AECOM2` | 76 Romania, all 76 Bucharest | Remote sample was predominantly non-European; no eligibility claim | 0 title-explicit; many engineering titles but limited software alignment | HTTP 200; 4,829 global | TARGET_PROVIDER_EMPLOYER |
| Deutsche Bank | [DB Romania careers](https://careers.db.com/explore-the-bank/locations/emea/romania) | Workday | CONFIRMED | host `db.wd3`; tenant `db`; site `DBWebsite` | Public text search: 129 Romania hits; at least 18 location-confirmed on sampled page | Not established | 3 title-explicit in sampled page; official Romania technology apprenticeship/graduate routes | HTTP 200; 1,050 global | VALID_SECONDARY_PROVIDER |
| CrowdStrike | [CrowdStrike careers](https://www.crowdstrike.com/en-us/careers/) | Workday | CONFIRMED | host `crowdstrike.wd5`; tenant `crowdstrike`; site `crowdstrikecareers` | 11 Romania search hits; 6 location-confirmed on sampled page | Europe remote is material but country eligibility must be read per posting | No title-explicit early-career role in sampled page | HTTP 200; 414 global | VALID_SECONDARY_PROVIDER |
| NXP | [NXP careers](https://www.nxp.com/company/about-nxp/careers:CAREERS) | Workday | CONFIRMED | host `nxp.wd3`; tenant `nxp`; site `careers` | 59 Romania search hits; at least 16 location-confirmed on sampled page | Not established | 5 title-explicit in sampled page; official Romania page describes paid internships | HTTP 200; 805 global | VALID_SECONDARY_PROVIDER |
| Adobe | [Adobe careers](https://careers.adobe.com/us/en) | Workday | CONFIRMED | host `adobe.wd5`; tenant `adobe`; site `external_experienced` | 30 Romania search hits; at least 19 location-confirmed on sampled page | Not established | Experienced site has 0 title-explicit early-career roles in sample | HTTP 200; 837 global | VALID_SECONDARY_PROVIDER |
| GE Vernova | [GE Vernova careers](https://careers.gevernova.com/jobs) | Workday | CONFIRMED | host `gevernova.wd5`; tenant `gevernova`; site `Vernova_ExternalSite` | 7 Romania search hits; 4 location-confirmed on sampled page | Not established | 0 title-explicit; profile fit mainly technical application/engineering | Not established | VALID_SECONDARY_PROVIDER |
| Paysafe | [Paysafe careers](https://www.paysafe.com/gb-en/careers/) | SAP SuccessFactors Career Site Builder | CONFIRMED | `jobs.paysafe.com`; all-jobs category `8822300` | 0 Romania among 10 visible global jobs | None observed | None observed | HTTP 200; 10 visible | DEFER_LOW_VALUE |
| SAP | [SAP jobs](https://jobs.sap.com/) | SAP SuccessFactors Career Site Builder | CONFIRMED | `jobs.sap.com` | Official Romania/Bucharest employer; current exact count not established | Not established | Official student/graduate programmes | Not established | VALID_SECONDARY_PROVIDER |
| Ericsson | [Ericsson jobs](https://jobs.ericsson.com/) | SAP SuccessFactors Career Site Builder | CONFIRMED | `jobs.ericsson.com` | Official Romanian technical presence; current exact count not established | Not established | Graduate/early-career routes exist; Romania count not established | Not established | VALID_SECONDARY_PROVIDER |
| EveryMatrix | [EveryMatrix careers](https://everymatrix.com/careers/) | Teamtailor | CONFIRMED | official custom career host; no public API tenant ID | Official Bucharest headquarters/development hub | Not established | No bounded current count established | Not established | REJECT_NO_PUBLIC_ACCESS |
| HARMAN | [HARMAN careers](https://jobs.harman.com/) | Avature | CONFIRMED | `jobsearch.harman.com/en_US/careers` | Official search includes Romanian jobs; count not established | Not established | Entry routes not established in bounded sample | HTTP 200; HTML search page ~206 KiB | DEFER_HIGH_COMPLEXITY |
| Oracle | [Oracle careers](https://careers.oracle.com/en/sites/jobsearch/) | Oracle Recruiting Cloud | CONFIRMED | public site `jobsearch`; backend coordinate not recorded | Official Romania/Bucharest hiring; exact count not established | Not established | Graduate routes exist; current Romania count not established | Not established | DEFER_HIGH_COMPLEXITY |
| FintechOS | [FintechOS careers](https://fintechos.com/careers/) | Custom first-party | CONFIRMED | none | 1 RO-based account role; Bucharest company/R&D evidence | None observed | No current technical early-career role | 3 visible jobs | DEFER_LOW_VALUE |
| Schneider Electric | [Schneider careers](https://careers.se.com/Defaultlandingpage/jobs/locations?lang=en-US) | Mixed/branded enterprise career layer | AMBIGUOUS | none | Bucharest/Romania are official filters; current technical application role observed | Not established | Official 3/6/12-month internship and graduate paths | Not established | HOLD_AMBIGUOUS |
| MultiversX | [MultiversX careers](https://multiversx.com/careers) | Unknown/custom | UNKNOWN | none | Romanian-founded; served page exposed no verifiable public job board | None established | None established | No board established | HOLD_AMBIGUOUS |
| Snyk | [Snyk open jobs](https://snyk.io/careers/all-jobs/) | Unknown client-rendered flow | AMBIGUOUS | none | No Romania board evidence established | Europe-oriented roles exist, but eligibility was not established | Official emerging-talent programme | No static board contract established | HOLD_AMBIGUOUS |
| Personio | [Personio careers](https://www.personio.com/about-personio/careers/) | Unknown/custom | AMBIGUOUS | none | Official office list does not include Romania | Europe offices and flexible work do not prove Romania eligibility | Official students/graduates route | Direct page returned 429 in bounded check | REJECT_NO_TARGET_GEOGRAPHY |
| Sysdig | [Sysdig open positions](https://www.sysdig.com/careers/open-positions) | Lever | CONFIRMED | `sysdig` from official page configuration | No current Romania evidence established | Europe-oriented security roles; eligibility per posting | None established | Existing supported provider | REJECT_DUPLICATE |
| Electronic Arts | [EA Romania careers](https://careers.ea.com/ea-studios/ea-romania/careers) | Custom/client-rendered | AMBIGUOUS | none | Official Bucharest studio and software/QA/security role families | None established | Official page explicitly includes entry-level hiring | No provider contract established | HOLD_AMBIGUOUS |
| Microsoft | [Microsoft jobs](https://jobs.careers.microsoft.com/global/en/search) | Custom/mixed first-party | AMBIGUOUS | none | Romanian office and support/engineering hiring are relevant; exact board contract not established | Remote labels require country-level verification | Student/graduate routes exist | No stable public provider contract established | HOLD_AMBIGUOUS |

### Phase 3.3A corrections

- **Sysdig is not an unsupported ATS gap.** Its current official page declares
  `const SITE = "sysdig"` beside `api.lever.co/v0/postings/${SITE}`; Lever is already
  supported. It is therefore a registry-research candidate, not a fifth-adapter candidate.
- **AECOM is SmartRecruiters.** Its official careers page publishes an `smrtr.io` link that
  resolves to the `AECOM2` SmartRecruiters company path.
- **CrowdStrike, Deutsche Bank, and GE Vernova are Workday**, with the composite host,
  tenant, and site coordinates shown above.
- **HARMAN is Avature**, and **Paysafe is SuccessFactors Career Site Builder**.
- MultiversX, Snyk, Personio, Schneider Electric, EA, and Microsoft remain deliberately
  ambiguous where the official flow did not expose a sufficiently strong generic contract.

## Public-access and data compatibility by provider

### SmartRecruiters

- [Documented public shape](https://developers.smartrecruiters.com/docs/endpoints):
  `GET https://api.smartrecruiters.com/v1/companies/{companyIdentifier}/postings`;
  detail: `GET .../postings/{postingId}`.
- [Unauthenticated Posting API access](https://developers.smartrecruiters.com/docs/authentication)
  is documented and exact boards returned 200 with no
  cookie or JavaScript. Parameters include `q`, `limit`, `offset`, `country`, `region`,
  `city`, and department/custom-field filters.
- Pagination is numeric `offset`/`limit`, maximum observed/documented page size 100, with
  `totalFound`. List entries carry `id`, `uuid`, title, company, released date, location,
  remote flag, department/function, employment and experience metadata, and a detail `ref`.
  Description, qualifications, additional information, `postingUrl`, and `applyUrl` are on
  the detail object, so ingestion is N+1.
- Observed list responses: 7.1–301.2 KiB for 3–100 entries; selected-board maximum was
  **308,391 bytes**. One Endava detail was **9,199 bytes**, with a 3,652-character
  description. The existing 10 MiB per-response bound is ample.
- The API has stable `id` and `uuid` values. `releasedDate`, structured location, remote,
  department/function, employment type, and experience level map well to `RawJob`,
  `RawLocationData`, and `RawCareerData`. A closing deadline and explicit applicant-country
  eligibility are generally absent; final eligibility must remain in screening.
- The future transport must add only the exact `api.smartrecruiters.com` destination family.
  Provider-emitted posting URLs are data, not request destinations.

### Workday

- Exact official boards accepted public JSON at the observed, undocumented CXS pattern:
  `POST https://{tenant}.{shard}.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs` with
  `{appliedFacets:{}, limit:20, offset:0, searchText:""}`; details were reachable at the
  returned `externalPath` under `/wday/cxs/{tenant}/{site}`.
- No cookies, authentication, browser, or CAPTCHA were needed in the sample. Pagination has
  `offset`, a 20-item page in the tested flow, and `total`; descriptions require detail
  requests. Five first pages were 3.5–19.7 KiB. One DB detail was 7,545 bytes with ID,
  title, description, location, date, and external URL.
- Identity and core `RawJob` fields are adequate, but provider coordinates are a composite
  shard host + tenant + site, location facets are board-specific IDs, and the CXS contract is
  not a documented public API. Strict subdomain validation and N+1 detail requests would be
  required. Maintenance and fixture-drift risk are materially higher than SmartRecruiters.

### SAP SuccessFactors Career Site Builder

- Confirmed public flows are branded HTML career sites such as `jobs.paysafe.com`,
  `jobs.sap.com`, and `jobs.ericsson.com`. SAP
  [documents Career Site Builder](https://help.sap.com/docs/successfactors-recruiting/setting-up-and-maintaining-sap-successfactors-recruiting/career-site-builder)
  and its search
  fields, but this research did not establish one stable, cross-customer unauthenticated JSON
  vacancy API suitable for the current adapter architecture.
- Paysafe's exact all-jobs page returned 200, ~94 KiB, and 10 public job links without
  authentication or JavaScript. It did not expose useful pagination at the current size.
  Customer-specific HTML/category routes and potential template drift raise maintenance
  risk; field completeness depends on detail HTML.
- Stable numeric job paths exist, but a generic adapter would be an HTML/marketing-site
  parser rather than a documented provider feed. This is deferred despite true pagination
  on larger CSB sites because current Paysafe Romania yield was zero.

### Teamtailor

- EveryMatrix's official careers page is served by Teamtailor assets and is publicly
  viewable. [Teamtailor's documented JSON API](https://docs.teamtailor.com/) supplies jobs,
  bodies, locations, remote status,
  pagination, and stable IDs, but requires an API key in the `Authorization` header even for
  public career-site data.
- The public HTML could be parsed, but that would substitute a site scraper for the
  authenticated documented API and would not meet Phase 3.3C's access criterion. No
  credential or browser path is proposed.

### Avature

- HARMAN's official flow links `jobsearch.harman.com/en_US/careers/SearchJobs`; the page
  identifies Avature and returned public HTML (~206 KiB) without authentication.
- The observed search contract is tenant-branded HTML/mixed JavaScript, not a documented
  cross-customer unauthenticated vacancy API. Search state, pagination, and field markup are
  customer-configurable. Stable job IDs appear available, but a generic implementation
  would carry high template and request-shape risk.

### Oracle Recruiting Cloud

- Oracle's official candidate site is public and uses Oracle Cloud infrastructure, but the
  public candidate-experience coordinate and REST/search shape are site-specific. A generic
  implementation would need a verified site coordinate, schema and paging contract per
  employer, plus strict host-family validation.
- The sample did not establish enough Romania yield or a second relevant confirmed employer
  to justify that engineering risk. No opaque backend identifier was copied or inferred.

### Custom and mixed first-party flows

FintechOS has a small first-party list; Schneider Electric, Snyk, MultiversX, EA, Microsoft,
and Personio expose custom or client-rendered flows whose underlying provider contract was
not strong enough to name generically. These flows are not one technical provider and cannot
support a generic fifth adapter. They remain documentation leads, not implementation input.

## Pagination, field, and health compatibility matrix

| Provider | Natural tenant attempt | Public format and pagination | Detail amplification | Observed response bound | `RawJob` gaps | Health/operational conclusion |
|---|---|---|---|---|---|---|
| SmartRecruiters | one company identifier | JSON; offset/limit 100; `totalFound`; country/query filters | yes, one detail per unique posting | list max 308,391 B; sampled detail 9,199 B | deadline and explicit applicant-country scope usually absent | Direct fit: wrap every page/detail in one monitor supplier; no rate limit or anti-bot encountered; hard aggregate cap required |
| Workday | one composite host/tenant/site board | JSON POST; observed offset/limit 20 and `total` | yes | list max 20,175 B; sampled detail 7,545 B | some department/workplace fields site-dependent; facet IDs opaque | One monitor attempt is possible, but undocumented contract, more pages, strict shard-host validation and schema drift raise risk; no rate limit/anti-bot encountered |
| SuccessFactors CSB | one branded career host/site | HTML/mixed; customer category/search pages; pagination template-dependent | usually detail HTML | Paysafe all-jobs ~96,402 B | structured remote, department and timestamp consistency uncertain | `getText` plus one supplier could classify transport failures, but customer-template parse errors and incomplete pagination make healthy completeness difficult to prove |
| Teamtailor | one company behind an API key | JSON: documented page number/size; public career HTML separately visible | API can include relationships; HTML details vary | not measured; no API call made because authentication is required | documented API is complete; unauthenticated HTML contract is not | 401/403 would map cleanly, but credentials violate this phase's selection criterion; reject rather than add secret-bearing source logic |
| Avature | one branded careers site | HTML/mixed; tenant-configured search state | likely detail page per posting | HARMAN search ~210,826 B | structured location/remote/timestamp fields not reliably generic | One supplier is possible, but template/search-state drift and page-completeness ambiguity would produce high parse/maintenance risk |
| Oracle Recruiting Cloud | one candidate-experience site coordinate | mixed public candidate UI/REST; site-specific paging coordinate | site-dependent | not measured; no opaque backend call made | core fields likely available, but exact public schema was not established | Monitor fit is conceptually possible; identifier/configuration and schema evidence are insufficient for safe generic implementation |

All candidates would reuse the existing HTTP taxonomy for status, timeout, network,
content-type, malformed-body, and per-response overflow. The decisive difference is whether
a successful attempt can truthfully mean “the bounded tenant result is complete.” Only
SmartRecruiters established that property with a documented, filterable pagination contract
and acceptable current request volume. No sampled provider returned CAPTCHA; Personio's
ambiguous first-party page returned HTTP 429, and it was not retried or used in scoring.

## Provider scorecard

Every dimension uses **5 = best / lowest adverse risk** and **1 = worst / highest adverse
risk**. For “operational and maintenance risk,” 5 therefore means low risk. The weights are
unchanged from the requested weighting: coverage 20%, Romania/Bucharest yield 20%,
entry/profile relevance 15%, public API feasibility 15%, pagination/response safety 10%,
data-model compatibility 10%, and operational/maintenance safety 10%.

| Provider | Confirmed relevant employers | Coverage 20% | RO/Bucharest yield 20% | Entry/profile 15% | Public API 15% | Paging/safety 10% | Data model 10% | Ops/maintenance safety 10% | Weighted total / 5 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| **SmartRecruiters** | 5 | 5 | 5 | 4 | 5 | 5 | 5 | 4 | **4.75** |
| Workday | 5 | 5 | 4 | 4 | 3 | 4 | 4 | 2 | **3.85** |
| SAP SuccessFactors CSB | 3 | 3 | 3 | 3 | 2 | 3 | 3 | 2 | **2.75** |
| Teamtailor | 1 | 1 | 5 | 3 | 1 | 2 | 4 | 2 | **2.60** |
| Oracle Recruiting Cloud | 1 | 1 | 3 | 2 | 2 | 3 | 4 | 2 | **2.30** |
| Avature | 1 | 1 | 3 | 2 | 1 | 2 | 3 | 1 | **1.85** |

### Additional scorecard dimensions

| Provider | Testability | Anti-bot/auth risk | Expected request volume | Response-size safety | Stable identity | Main maintenance risk |
|---|---:|---:|---:|---:|---:|---|
| SmartRecruiters | 5 | 5 | 3 (list + detail N+1) | 5 | 5 | Public detail schema drift |
| Workday | 4 | 4 | 2 (20-item pages + N+1) | 5 | 4 | Undocumented CXS and shard/site coordinates |
| SuccessFactors CSB | 3 | 4 | 3 | 4 | 4 | Customer-specific HTML templates |
| Teamtailor | 5 with key; 2 without | 1 | 4 | 5 | 5 | Documented structured access requires secret |
| Oracle Recruiting Cloud | 3 | 3 | 3 | 4 | 4 | Site-specific opaque coordinate/schema |
| Avature | 2 | 3 | 2 | 3 | 3 | Configurable HTML/search state |

## Why alternatives were not selected

- **Workday:** nearly equal employer/yield value, but an undocumented CXS contract, 20-item
  pages, composite shard/tenant/site configuration, N+1 details, and more host validation
  make it a materially higher-maintenance first choice. It is the strongest next candidate.
- **SuccessFactors:** the public Career Site Builder is accessible, but no stable generic
  unauthenticated JSON feed was established; the strongest original lead, Paysafe, had no
  current Romania vacancy in a 10-job global board.
- **Teamtailor:** EveryMatrix is exceptionally relevant, but the official structured API
  requires a secret API key. Parsing the public career HTML would violate the selection
  preference for public structured access and raise template risk.
- **Oracle Recruiting Cloud:** one confirmed employer, site-specific coordinates, weaker
  demonstrated early-career Romania yield, and a less certain generic public contract.
- **Avature:** one relevant employer and a large configurable HTML/mixed search flow without
  a stable cross-customer public vacancy contract.
- **Custom/mixed flows:** these are unrelated implementations, so an adapter would become a
  generic/company-specific scraper rather than a provider adapter.

## Source-health and bounding conclusion

SmartRecruiters naturally maps **all list pages and detail calls for one company identifier
to one `TenantFetchMonitor` supplier invocation and one final attempt row**. Physical calls
retain `ExternalHttpClient`'s three-attempt policy for timeout/I/O/429/5xx; 404/410 remains
`INVALID_TENANT`, 401/403 `AUTHORIZATION_ERROR`, 429 `RATE_LIMITED`, 5xx `SERVER_ERROR`,
malformed/schema failures `RESPONSE_PARSE_ERROR`, and per-response overflow
`RESPONSE_TOO_LARGE`.

The Phase 3.3C design must keep the existing 10 MiB per-response limit and add aggregate
bounds, repeated-page detection, total consistency checks, and all-or-nothing tenant
semantics. It must never write one health row per page. The complete implementation contract
is in [next-ats-provider-spec.md](next-ats-provider-spec.md).
