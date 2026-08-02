/**
 * Deterministic, browser-free selection and validation of vacancy fields from
 * bounded rendered-page candidates.
 *
 * Priority: JSON-LD JobPosting -> embedded provider state -> metadata ->
 * provider-specific DOM -> generic DOM. Only externalId, title and a public
 * HTTPS sourceUrl are required; all other fields are optional and bounded.
 */
import { screenStructure } from "./urlPolicy.js";
import { truncateUtf16 } from "./text.js";
import type {
  DescriptionEvidence,
  DetailProvider,
  ExtractRequest,
  ExtractResponse,
  ExtractedJob,
  RawPageData,
  TextEvidence,
} from "./types.js";

const BOUNDS = {
  externalId: { min: 1, max: 200 },
  title: { min: 2, max: 300 },
  company: { min: 1, max: 200 },
  location: { min: 1, max: 300 },
  employmentType: { min: 1, max: 80 },
  workplaceType: { min: 1, max: 80 },
  provider: { min: 1, max: 40 },
  publishedAt: { min: 4, max: 40 },
  url: { min: 8, max: 2000 },
  descriptionMin: 40,
} as const;

const MAX_STRUCTURED_NODES = 1500;
const UNSAFE_CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/;

interface Candidate<T extends string> {
  value: string;
  source: T;
}

export function buildResult(
  request: ExtractRequest,
  page: RawPageData,
  maxDescriptionChars: number,
): ExtractResponse {
  const protectedStatus = classifyProtected(page);
  if (protectedStatus) return { requestId: request.requestId, status: protectedStatus };

  const provider =
    page.dom.provider ?? detectProvider(page.finalUrl) ?? detectProvider(request.url);
  if (isKnownUnsupportedPage(page, provider)) {
    return { requestId: request.requestId, status: "UNSUPPORTED" };
  }

  const allBlocks = [...page.jsonLdBlocks, ...page.embeddedState];
  const postings = allBlocks.flatMap(collectJobPostings);
  const requestedId = provider === null ? null : deriveExternalId(provider, request.url);
  const posting = selectBestPosting(postings, provider, requestedId, page.finalUrl);
  const selectedPostings = posting ? [posting] : [];
  const records = page.embeddedState.flatMap((block) => collectProviderRecords(block, provider));

  const title = pickText(
    [
      jsonLdField(selectedPostings, ["title", "name"]),
      embeddedField(records, providerTitleKeys(provider)),
      metaField(page.meta, ["og:title", "twitter:title"]),
      domField(page.dom.title, "DOM"),
    ],
    BOUNDS.title,
  );

  const sourceUrl = pickSourceUrl(selectedPostings, records, page);
  const externalId = pickText(
    [
      jsonLdIdentifier(selectedPostings),
      embeddedIdentifier(records),
      domField(page.dom.externalId ?? null, "DOM"),
      sourceUrl ? domField(deriveExternalId(provider, sourceUrl), "DOM") : null,
      domField(requestedId, "DOM"),
    ],
    BOUNDS.externalId,
  );

  const hasVacancyStructure =
    selectedPostings.length > 0 ||
    records.length > 0 ||
    requestedId !== null ||
    page.dom.externalId != null ||
    page.dom.title !== null ||
    page.meta["og:title"] !== undefined;

  if (title === null || externalId === null || sourceUrl === null) {
    return {
      requestId: request.requestId,
      status: hasVacancyStructure ? "INSUFFICIENT_DATA" : "UNSUPPORTED",
    };
  }

  const company = pickText(
    [
      jsonLdCompany(selectedPostings),
      embeddedCompany(records),
      metaField(page.meta, ["og:site_name"]),
      domField(page.dom.company, "DOM"),
    ],
    BOUNDS.company,
  );
  const description = pickDescription(
    [
      jsonLdDescription(selectedPostings),
      embeddedDescription(records),
      metaField(page.meta, ["og:description", "description"]),
      domDescription(page.dom.description),
    ],
    maxDescriptionChars,
  );
  const location = pickText(
    [
      jsonLdLocation(selectedPostings),
      embeddedLocation(records),
      metaField(page.meta, ["job:location"]),
      domField(page.dom.location, "DOM"),
    ],
    BOUNDS.location,
  );
  const employmentType = pickText(
    [
      jsonLdField(selectedPostings, ["employmentType"]),
      embeddedEmploymentType(records),
      metaField(page.meta, ["job:employment_type"]),
      domField(page.dom.employmentType, "DOM"),
    ],
    BOUNDS.employmentType,
  );
  const workplaceType = pickText(
    [
      jsonLdField(selectedPostings, ["jobLocationType"]),
      embeddedWorkplaceType(records),
      domField(page.dom.workplaceType ?? null, "DOM"),
    ],
    BOUNDS.workplaceType,
  );
  const publishedAt = pickPublishedAt(
    selectedPostings,
    records,
    page.dom.publishedAt ?? page.meta["job:published_at"] ?? null,
  );
  const applicationUrl = pickApplicationUrl(selectedPostings, records, page);

  const job: ExtractedJob = {
    externalId: externalId.value,
    title: title.value,
    sourceUrl,
    company: company?.value ?? null,
    location: location?.value ?? null,
    description: description?.value ?? null,
    employmentType: employmentType?.value ?? null,
    workplaceType: workplaceType?.value ?? null,
    publishedAt,
    applicationUrl,
    provider,
    canonicalUrl: sourceUrl,
  };

  return {
    requestId: request.requestId,
    status: "EXTRACTED",
    finalUrl: sourceUrl,
    sourceType: "BROWSER",
    job,
    evidence: {
      titleSource: title.source,
      companySource: company?.source ?? null,
      descriptionSource: description?.source ?? null,
    },
  };
}

function classifyProtected(
  page: RawPageData,
): "AUTH_REQUIRED" | "CHALLENGE_DETECTED" | "BLOCKED" | null {
  if (page.signals.challengeMarker) return "CHALLENGE_DETECTED";
  if (page.signals.hasPasswordField || page.signals.hasLoginForm) return "AUTH_REQUIRED";
  if (page.signals.accessDeniedTitle) return "BLOCKED";
  return null;
}

function isKnownUnsupportedPage(page: RawPageData, provider: DetailProvider | null): boolean {
  if (provider === null) return false;
  const title = normalizeShort(page.dom.title ?? page.meta["document:title"] ?? "")?.toLowerCase() ?? "";
  let url: URL | null = null;
  try {
    url = new URL(page.finalUrl);
  } catch {
    // Invalid final URLs are handled as insufficient later.
  }
  if (provider === "GREENHOUSE" && url?.searchParams.get("error") === "true") return true;
  return (
    title === "job not found" ||
    title === "position not found" ||
    title.startsWith("current openings at ") ||
    title.includes("this job is no longer available")
  );
}

/* ----------------------------- structured data -------------------------- */

function parseJson(block: string): unknown | null {
  try {
    return JSON.parse(stripJsonComments(block));
  } catch {
    return null;
  }
}

function walkObjects(root: unknown): Record<string, unknown>[] {
  if (root === null) return [];
  const objects: Record<string, unknown>[] = [];
  const pending: unknown[] = [root];
  while (pending.length > 0 && objects.length < MAX_STRUCTURED_NODES) {
    const current = pending.shift();
    if (Array.isArray(current)) {
      pending.push(...current.slice(0, MAX_STRUCTURED_NODES - objects.length));
      continue;
    }
    if (current === null || typeof current !== "object") continue;
    const record = current as Record<string, unknown>;
    objects.push(record);
    for (const value of Object.values(record)) {
      if (value !== null && typeof value === "object") pending.push(value);
    }
  }
  return objects;
}

function collectJobPostings(block: string): Record<string, unknown>[] {
  return walkObjects(parseJson(block)).filter(isJobPosting);
}

function isJobPosting(node: Record<string, unknown>): boolean {
  const type = node["@type"];
  if (typeof type === "string") return type.toLowerCase() === "jobposting";
  return Array.isArray(type) && type.some(
    (value) => typeof value === "string" && value.toLowerCase() === "jobposting",
  );
}

function collectProviderRecords(
  block: string,
  provider: DetailProvider | null,
): Record<string, unknown>[] {
  if (provider === null) return [];
  return walkObjects(parseJson(block)).filter((record) => isProviderRecord(record, provider));
}

function isProviderRecord(record: Record<string, unknown>, provider: DetailProvider): boolean {
  const has = (...keys: string[]): boolean => keys.some((key) => nonblankScalar(record[key]) !== null);
  switch (provider) {
    case "GREENHOUSE":
      return has("title") && has("id", "absolute_url", "content");
    case "ASHBY":
      return has("title") && has("id", "jobUrl", "descriptionHtml", "descriptionPlain");
    case "LEVER":
      return has("text", "title") && has("id", "hostedUrl", "description", "descriptionPlain");
    case "RECRUITEE":
      return has("title", "position") && has("id", "guid", "careers_url", "description");
    case "LINKEDIN":
      return false;
  }
}

function selectBestPosting(
  postings: Record<string, unknown>[],
  provider: DetailProvider | null,
  expectedId: string | null,
  finalUrl: string,
): Record<string, unknown> | null {
  let best: Record<string, unknown> | null = null;
  let bestScore = -1;
  for (const posting of postings) {
    const identifier = scalarIdentifier(posting["identifier"]) ?? scalarIdentifier(posting["@id"]);
    const postingUrl = nonblankScalar(posting["url"]);
    const urlId = postingUrl ? deriveExternalId(provider, postingUrl) : null;
    let score = 0;
    if (expectedId && (identifier === expectedId || urlId === expectedId)) score += 100;
    if (postingUrl && sameNormalizedUrl(postingUrl, finalUrl)) score += 50;
    for (const field of ["title", "name", "description", "hiringOrganization", "jobLocation"]) {
      if (posting[field] != null) score++;
    }
    if (score > bestScore) {
      best = posting;
      bestScore = score;
    }
  }
  return best;
}

function sameNormalizedUrl(left: string, right: string): boolean {
  try {
    const a = new URL(left);
    const b = new URL(right);
    a.hash = "";
    b.hash = "";
    return a.toString() === b.toString();
  } catch {
    return false;
  }
}

function jsonLdField(
  postings: Record<string, unknown>[],
  keys: string[],
): Candidate<TextEvidence> | null {
  for (const posting of postings) {
    for (const key of keys) {
      const value = nonblankScalar(posting[key]);
      if (value !== null) return { value, source: "JSON_LD" };
    }
  }
  return null;
}

function jsonLdIdentifier(postings: Record<string, unknown>[]): Candidate<TextEvidence> | null {
  for (const posting of postings) {
    const value = scalarIdentifier(posting["identifier"]) ?? scalarIdentifier(posting["@id"]);
    if (value !== null) return { value, source: "JSON_LD" };
  }
  return null;
}

function scalarIdentifier(value: unknown): string | null {
  const scalar = nonblankScalar(value);
  if (scalar !== null) return scalar;
  if (value !== null && typeof value === "object" && !Array.isArray(value)) {
    const record = value as Record<string, unknown>;
    return nonblankScalar(record["value"]) ?? nonblankScalar(record["name"])
      ?? nonblankScalar(record["@id"]);
  }
  return null;
}

function jsonLdCompany(postings: Record<string, unknown>[]): Candidate<TextEvidence> | null {
  for (const posting of postings) {
    const org = posting["hiringOrganization"];
    const scalar = nonblankScalar(org);
    if (scalar !== null) return { value: scalar, source: "JSON_LD" };
    if (org !== null && typeof org === "object" && !Array.isArray(org)) {
      const name = nonblankScalar((org as Record<string, unknown>)["name"]);
      if (name !== null) return { value: name, source: "JSON_LD" };
    }
  }
  return null;
}

function jsonLdDescription(
  postings: Record<string, unknown>[],
): Candidate<DescriptionEvidence> | null {
  const raw = jsonLdField(postings, ["description"]);
  return raw ? { value: htmlToText(raw.value), source: "JSON_LD" } : null;
}

function jsonLdLocation(postings: Record<string, unknown>[]): Candidate<TextEvidence> | null {
  for (const posting of postings) {
    const parts: string[] = [];
    const raw = posting["jobLocation"];
    const locations = Array.isArray(raw) ? raw : [raw];
    for (const entry of locations) {
      if (entry === null || typeof entry !== "object" || Array.isArray(entry)) continue;
      const record = entry as Record<string, unknown>;
      const address = record["address"];
      const scalar = nonblankScalar(address);
      if (scalar !== null) parts.push(scalar);
      else if (address !== null && typeof address === "object" && !Array.isArray(address)) {
        const a = address as Record<string, unknown>;
        for (const key of ["addressLocality", "addressRegion", "addressCountry"]) {
          const value = nonblankScalar(a[key]);
          if (value !== null) parts.push(value);
        }
      }
      const name = nonblankScalar(record["name"]);
      if (parts.length === 0 && name !== null) parts.push(name);
    }
    if (parts.length > 0) return { value: [...new Set(parts)].join(", "), source: "JSON_LD" };
  }
  return null;
}

/* ----------------------------- embedded provider state ------------------ */

function providerTitleKeys(provider: DetailProvider | null): string[] {
  return provider === "LEVER" ? ["text", "title"] : ["title", "position", "name"];
}

function embeddedField(
  records: Record<string, unknown>[],
  keys: string[],
): Candidate<TextEvidence> | null {
  for (const record of records) {
    for (const key of keys) {
      const value = nonblankScalar(record[key]);
      if (value !== null) return { value, source: "EMBEDDED" };
    }
  }
  return null;
}

function embeddedIdentifier(records: Record<string, unknown>[]): Candidate<TextEvidence> | null {
  return embeddedField(records, ["id", "guid", "externalId", "jobId"]);
}

function embeddedCompany(records: Record<string, unknown>[]): Candidate<TextEvidence> | null {
  return embeddedField(records, ["company_name", "companyName", "organizationName", "company"]);
}

function embeddedDescription(
  records: Record<string, unknown>[],
): Candidate<DescriptionEvidence> | null {
  const value = embeddedField(records, [
    "descriptionPlain", "descriptionHtml", "description", "content", "requirements",
  ]);
  return value ? { value: htmlToText(value.value), source: "EMBEDDED" } : null;
}

function embeddedLocation(records: Record<string, unknown>[]): Candidate<TextEvidence> | null {
  for (const record of records) {
    const direct = nonblankScalar(record["location"]);
    if (direct !== null) return { value: direct, source: "EMBEDDED" };
    const location = record["location"];
    if (location !== null && typeof location === "object" && !Array.isArray(location)) {
      const name = nonblankScalar((location as Record<string, unknown>)["name"]);
      if (name !== null) return { value: name, source: "EMBEDDED" };
    }
    const categories = record["categories"];
    if (categories !== null && typeof categories === "object" && !Array.isArray(categories)) {
      const value = nonblankScalar((categories as Record<string, unknown>)["location"]);
      if (value !== null) return { value, source: "EMBEDDED" };
    }
  }
  return null;
}

function embeddedEmploymentType(records: Record<string, unknown>[]): Candidate<TextEvidence> | null {
  const direct = embeddedField(records, ["employmentType", "employment_type_code"]);
  if (direct) return direct;
  for (const record of records) {
    const categories = record["categories"];
    if (categories !== null && typeof categories === "object" && !Array.isArray(categories)) {
      const value = nonblankScalar((categories as Record<string, unknown>)["commitment"]);
      if (value !== null) return { value, source: "EMBEDDED" };
    }
  }
  return null;
}

function embeddedWorkplaceType(records: Record<string, unknown>[]): Candidate<TextEvidence> | null {
  const direct = embeddedField(records, ["workplaceType", "locationType"]);
  if (direct) return direct;
  for (const record of records) {
    if (record["remote"] === true || record["isRemote"] === true) {
      return { value: "Remote", source: "EMBEDDED" };
    }
    if (record["hybrid"] === true) return { value: "Hybrid", source: "EMBEDDED" };
    if (record["on_site"] === true) return { value: "OnSite", source: "EMBEDDED" };
  }
  return null;
}

/* ----------------------------- URLs, dates and provider ----------------- */

function detectProvider(value: string): DetailProvider | null {
  try {
    const host = new URL(value).hostname.toLowerCase();
    if (host === "boards.greenhouse.io" || host === "job-boards.greenhouse.io") return "GREENHOUSE";
    if (host === "jobs.ashbyhq.com") return "ASHBY";
    if (host === "jobs.lever.co") return "LEVER";
    if (host.endsWith(".recruitee.com")) return "RECRUITEE";
    if (host === "linkedin.com" || host.endsWith(".linkedin.com")) return "LINKEDIN";
  } catch {
    return null;
  }
  return null;
}

function deriveExternalId(provider: DetailProvider | null, value: string): string | null {
  try {
    const url = new URL(value);
    const path = url.pathname;
    const match =
      provider === "GREENHOUSE"
        ? path.match(/\/jobs\/(\d+)(?:\/|$)/i)
        : provider === "ASHBY"
          ? path.match(/\/([0-9a-f]{8}-[0-9a-f-]{27,})\/?$/i)
          : provider === "LEVER"
            ? path.match(/\/[^/]+\/([0-9a-f-]{20,})\/?$/i)
            : provider === "RECRUITEE"
              ? path.match(/\/o\/([^/]+)\/?$/i)
              : provider === "LINKEDIN"
                ? path.match(/\/jobs\/view\/(?:[^/]*-)?(\d{6,})\/?$/i)
                : path.match(/\/([^/]+)\/?$/);
    const decoded = match?.[1] ? decodeURIComponent(match[1]).trim() : "";
    return decoded && decoded.length <= BOUNDS.externalId.max ? decoded : null;
  } catch {
    return null;
  }
}

function pickSourceUrl(
  postings: Record<string, unknown>[],
  records: Record<string, unknown>[],
  page: RawPageData,
): string | null {
  const candidates: string[] = [];
  for (const posting of postings) {
    const value = nonblankScalar(posting["url"]);
    if (value) candidates.push(value);
  }
  for (const record of records) {
    for (const key of ["absolute_url", "jobUrl", "hostedUrl", "careers_url", "url"]) {
      const value = nonblankScalar(record[key]);
      if (value) candidates.push(value);
    }
  }
  if (page.meta["canonical"]) candidates.push(page.meta["canonical"]);
  candidates.push(page.finalUrl);
  for (const candidate of candidates) {
    const resolved = resolveSameOriginHttps(candidate, page.finalUrl);
    if (resolved !== null) return resolved;
  }
  return null;
}

function pickApplicationUrl(
  postings: Record<string, unknown>[],
  records: Record<string, unknown>[],
  page: RawPageData,
): string | null {
  const candidates: string[] = [];
  for (const posting of postings) {
    for (const key of ["applicationUrl", "applyUrl", "url"]) {
      const value = nonblankScalar(posting[key]);
      if (value) candidates.push(value);
    }
  }
  for (const record of records) {
    for (const key of ["applicationUrl", "applyUrl", "apply_url", "application_url"]) {
      const value = nonblankScalar(record[key]);
      if (value) candidates.push(value);
    }
  }
  if (page.dom.applicationUrl) candidates.push(page.dom.applicationUrl);
  for (const candidate of candidates) {
    const resolved = resolvePublicHttps(candidate, page.finalUrl);
    if (resolved !== null) return resolved;
  }
  return null;
}

function resolveSameOriginHttps(candidate: string, base: string): string | null {
  const resolved = resolvePublicHttps(candidate, base);
  if (resolved === null) return null;
  try {
    return new URL(resolved).origin === new URL(base).origin ? resolved : null;
  } catch {
    return null;
  }
}

function resolvePublicHttps(candidate: string, base: string): string | null {
  try {
    const resolved = new URL(candidate, base);
    if (resolved.protocol !== "https:") return null;
    if (resolved.toString().length < BOUNDS.url.min || resolved.toString().length > BOUNDS.url.max) return null;
    const screened = screenStructure(resolved.toString());
    return screened.ok ? screened.url.toString() : null;
  } catch {
    return null;
  }
}

function pickPublishedAt(
  postings: Record<string, unknown>[],
  records: Record<string, unknown>[],
  domValue: string | null,
): string | null {
  const candidates: unknown[] = [];
  for (const posting of postings) candidates.push(posting["datePosted"]);
  for (const record of records) {
    for (const key of ["publishedAt", "published_at", "updated_at", "createdAt"]) {
      candidates.push(record[key]);
    }
  }
  candidates.push(domValue);
  for (const raw of candidates) {
    let value = nonblankScalar(raw);
    if (typeof raw === "number" && Number.isFinite(raw)) {
      const date = new Date(raw);
      value = Number.isNaN(date.getTime()) ? null : date.toISOString();
    }
    if (
      value !== null &&
      value.length >= BOUNDS.publishedAt.min &&
      value.length <= BOUNDS.publishedAt.max &&
      /^\d{4}-\d{2}-\d{2}/.test(value) &&
      !UNSAFE_CONTROL.test(value)
    ) return value;
  }
  return null;
}

/* ----------------------------- selection and sanitisation --------------- */

function domField(value: string | null, source: TextEvidence): Candidate<TextEvidence> | null {
  return value !== null && value.trim() !== "" ? { value, source } : null;
}

function domDescription(value: string | null): Candidate<DescriptionEvidence> | null {
  return value !== null && value.trim() !== "" ? { value, source: "DOM" } : null;
}

function metaField(meta: Record<string, string>, keys: string[]): Candidate<TextEvidence> | null {
  for (const key of keys) {
    const value = meta[key];
    if (typeof value === "string" && value.trim() !== "") return { value, source: "META" };
  }
  return null;
}

function pickText<T extends string>(
  candidates: (Candidate<T> | null)[],
  bounds: { min: number; max: number },
): Candidate<T> | null {
  for (const candidate of candidates) {
    if (candidate === null) continue;
    const cleaned = normalizeShort(candidate.value);
    if (cleaned !== null && cleaned.length >= bounds.min && cleaned.length <= bounds.max) {
      return { value: cleaned, source: candidate.source };
    }
  }
  return null;
}

function pickDescription(
  candidates: (Candidate<DescriptionEvidence> | null)[],
  maxChars: number,
): Candidate<DescriptionEvidence> | null {
  for (const candidate of candidates) {
    if (candidate === null) continue;
    const cleaned = normalizeMultiline(candidate.value, maxChars);
    if (cleaned !== null && cleaned.length >= BOUNDS.descriptionMin) {
      return { value: cleaned, source: candidate.source };
    }
  }
  return null;
}

function nonblankScalar(value: unknown): string | null {
  if (typeof value === "string") return value.trim() ? value.trim() : null;
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return null;
}

function normalizeShort(value: string): string | null {
  if (UNSAFE_CONTROL.test(value)) return null;
  const cleaned = value.replace(/\s+/g, " ").trim();
  return cleaned || null;
}

function normalizeMultiline(value: string, maxChars: number): string | null {
  if (UNSAFE_CONTROL.test(value)) return null;
  const collapsed = value
    .replace(/\r\n?/g, "\n")
    .replace(/[ \t\f\v]+/g, " ")
    .replace(/[ \t]*\n[ \t]*/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
  if (collapsed === "") return null;
  return collapsed.length > maxChars ? truncateUtf16(collapsed, maxChars).trim() : collapsed;
}

export function htmlToText(value: string): string {
  const bounded = truncateUtf16(value, 400000);
  const decoded = decodeEntities(bounded);
  return decoded
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<\s*(script|style|noscript)[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi, " ")
    .replace(/<\s*br\s*\/?\s*>/gi, "\n")
    .replace(/<\/?\s*(p|div|li|ul|ol|h[1-6]|section|article|blockquote|tr)[^>]*>/gi, "\n")
    .replace(/<[^>]+>/g, " ");
}

function decodeEntities(value: string): string {
  const named: Record<string, string> = {
    amp: "&", apos: "'", gt: ">", lt: "<", nbsp: " ", quot: '"',
  };
  return value.replace(/&(#x[0-9a-f]{1,6}|#\d{1,7}|amp|apos|gt|lt|nbsp|quot);/gi, (match, token: string) => {
    const lower = token.toLowerCase();
    if (lower.startsWith("#")) {
      const hex = lower.startsWith("#x");
      const code = Number.parseInt(lower.slice(hex ? 2 : 1), hex ? 16 : 10);
      if (!Number.isFinite(code) || code <= 0 || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) {
        return " ";
      }
      const decoded = String.fromCodePoint(code);
      return UNSAFE_CONTROL.test(decoded) ? " " : decoded;
    }
    return named[lower] ?? match;
  });
}

function stripJsonComments(value: string): string {
  return value.replace(/^\s*<!--/, "").replace(/-->\s*$/, "").trim();
}
