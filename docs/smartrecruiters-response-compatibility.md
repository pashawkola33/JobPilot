# SmartRecruiters response-shape compatibility (Phase 3.3E)

Why `Ubisoft2`, `Endava`, and `Gameloft` failed to parse in Phase 3.3D, and the
provider-generic fix that resolved all three.

## Original failure

Controlled run `c7643227-60fe-4fa6-8ea7-57ccd5e39e7a` recorded, for each of the three:

| Field | Value |
|---|---|
| category | `RESPONSE_PARSE_ERROR` |
| http status | `null` — the endpoint answered; nothing was rejected at HTTP level |
| fetched | 0 |
| duration | 250 ms / 353 ms / 402 ms |
| detail | `Could not parse the smartrecruiters jobs response for tenant <company>` |

The persisted detail named no stage, because every one of the parser's ~15 validation
points threw the same contextless `ExternalHttpException(MALFORMED_JSON, null)`. That was
the first thing to fix.

## Root cause

`department.id` — and, on the same boards, `function.label` — are serialised by
SmartRecruiters as a **JSON number** for some companies and a **string** for others. Both
are valid Posting API responses.

The adapter read every reference field through `optionalText`, which requires
`JsonNode.isTextual()` and rejects anything else. A numeric `id` therefore failed the whole
tenant under all-or-nothing semantics.

Two details explain why this was invisible in 3.3D:

- the **list** responses for these companies carry string ids, so both list partitions
  parsed cleanly; only the **detail** response uses numbers. That is why the failures were
  fast — two list calls, then the first detail call rejected;
- `BoschGroup` and `AECOM2` return strings in both stages, so the same adapter code path
  worked for them.

Confirmed directly by the adapter once diagnostics existed:

```
Ubisoft2: FAILED after 1652 ms, category=MALFORMED_JSON,
          detail=detail.department.id: expected STRING but was NUMBER
```

## Investigation method

Two opt-in, test-scope probes, both disabled by default and never wired into production:

| Probe | Cost | Purpose |
|---|---|---|
| `SmartRecruitersResponseShapeProbe` | one request per company | structural summary: field names, JSON types, counts, and a per-entry sweep against the adapter's rules |
| `SmartRecruitersLiveTenantProbe` | full tenant fetch | runs the real adapter and prints its bounded parse detail |

Both use the ordinary `ExternalHttpClient`, so host policy, timeouts, and the 10 MiB bound
apply unchanged. Neither records field values: the structural probe prints JSON **type**
names, counts, and string *lengths* only.

The structural sweep proved the list stage was clean for all three (0 rejected entries out
of 3, 97, and 5 respectively, plus 1 for the Ubisoft2 remote partition), which is what
narrowed the search to the detail stage.

## The fix

One new provider-generic helper, `optionalScalarText`, used **only** for the `id` and
`label` of a reference object (`department`, `function`, `typeOfEmployment`,
`experienceLevel`):

- `STRING` → used as-is;
- `NUMBER` or `BOOLEAN` → rendered with `asText()`;
- `OBJECT` or `ARRAY` → still rejected;
- absent or null → still optional.

This is safe because reference values are display and reference text only. They never
contribute to the posting's stable identity, its canonical URL, or any eligibility
decision. Every mandatory field — `id`, `name`, `postingUrl`, `company.identifier`,
`jobAd.sections.jobDescription` — still goes through the strict `requiredText` path, and
`location` fields remain strictly textual because no evidence suggested otherwise.

No company-specific branch exists anywhere in the adapter.

### Diagnostics

`ExternalHttpException` gained a `parseDetail` field carrying a bounded phrase such as
`detail.department.id: expected STRING but was NUMBER`. It is built only from compile-time
field paths and JSON type names, never from a field value, response fragment, URL, or
header, and `TenantFailureClassifier` appends it to the health message through the existing
`SafeErrorText` bound. A vague "could not parse" is now actionable.

## Fixtures

Named by shape, not by employer, and fully synthetic:

- `detail-numeric-label-id.json` — numeric `department.id` and numeric `function.label`;
- `detail-object-label-id.json` — a container in the same position, which must still fail.

## Post-fix validation

Each held company was re-run through the real adapter:

| Company | Result | Postings mapped | Duration | Representative posting |
|---|---|---|---|---|
| `Ubisoft2` | SUCCESS | 4 | 1.9 s | stable external ID, tenant preserved, https canonical URL, 2,254-char description |
| `Endava` | SUCCESS | 102 | 14.7 s | stable external ID, tenant preserved, https canonical URL, 5,377-char description |
| `Gameloft` | SUCCESS | 5 | 0.9 s | stable external ID, tenant preserved, https canonical URL, 2,719-char description |

`BoschGroup` and `AECOM2` behaviour is unchanged; the standard fixture still maps
byte-for-byte identically, asserted by a characterization test.

## Phase 3.3F readiness

| Company | 3.3E classification | 3.3F outcome |
|---|---|---|
| `Ubisoft2` | READY_FOR_CONTROLLED_INGESTION | **ACTIVATE** — SUCCESS, 4 fetched, 0.72 s |
| `Endava` | READY_FOR_CONTROLLED_INGESTION | **ACTIVATE** — SUCCESS, 102 fetched, 15.2 s |
| `Gameloft` | READY_FOR_CONTROLLED_INGESTION | **ACTIVATE** — SUCCESS, 5 fetched, 0.91 s |

Each parses both list partitions with safe pagination metadata, hydrates representative
details, needs no authentication or browser execution, yields a stable ID and public HTTPS
URL, stays far below 10 MiB, and requires no employer-specific branch.

Phase 3.3E activated none of them. Phase 3.3F ran one controlled cycle
(`4d1ddf9c-07b0-488e-8bc1-23bc6b1c16c0`, 53 attempts, 52 success) in which **all five
SmartRecruiters companies succeeded with no parse error of any kind**, and activated the
three. The tracked registry is now `BoschGroup,AECOM2,Ubisoft2,Endava,Gameloft` — 53
tenants in total.

The two probe classes described above were investigation scaffolding and were removed once
the root cause was confirmed; the compatibility behaviour they uncovered is now covered by
the synthetic fixtures and characterization tests instead.
