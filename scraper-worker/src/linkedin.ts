/** Pure validation and normalization for public LinkedIn guest search pages. */
import { screenStructure } from "./urlPolicy.js";
import type { ExtractRequest, RawPageData, SearchJob, SearchResponse } from "./types.js";

const SEARCH_PATHS = ["/jobs/search", "/jobs-guest/jobs/api/seeMoreJobPostings/search"];

export function isLinkedInSearchUrl(rawUrl: string): boolean {
  const screened = screenStructure(rawUrl);
  if (!screened.ok || screened.url.protocol !== "https:") return false;
  if (!isLinkedInHost(screened.url.hostname)) return false;
  const path = screened.url.pathname.replace(/\/$/, "");
  return SEARCH_PATHS.includes(path);
}

export function buildLinkedInSearchResult(
  request: ExtractRequest,
  page: RawPageData,
  maxResults: number,
): SearchResponse {
  if (page.signals.challengeMarker) {
    return { requestId: request.requestId, status: "CHALLENGE_DETECTED" };
  }
  if (page.signals.hasPasswordField || page.signals.hasLoginForm) {
    return { requestId: request.requestId, status: "AUTH_REQUIRED" };
  }
  if (page.signals.accessDeniedTitle) {
    return { requestId: request.requestId, status: "BLOCKED" };
  }
  if (!isLinkedInSearchUrl(page.finalUrl)) {
    return { requestId: request.requestId, status: "UNSUPPORTED" };
  }

  const jobs: SearchJob[] = [];
  const seen = new Set<string>();
  for (const raw of page.searchJobs ?? []) {
    if (jobs.length >= maxResults) break;
    const title = bounded(raw.title, 2, 300);
    const company = bounded(raw.company, 1, 200);
    const normalized = normalizeJobUrl(raw.url, page.finalUrl);
    if (title === null || company === null || normalized === null || seen.has(normalized.url)) continue;
    seen.add(normalized.url);
    jobs.push({
      externalId: normalized.externalId,
      title,
      company,
      location: bounded(raw.location, 1, 300),
      url: normalized.url,
    });
  }

  return {
    requestId: request.requestId,
    status: "EXTRACTED",
    finalUrl: page.finalUrl,
    sourceType: "BROWSER",
    jobs,
  };
}

export function isLinkedInHost(hostname: string): boolean {
  const host = hostname.toLowerCase().replace(/\.$/, "");
  return host === "linkedin.com" || host.endsWith(".linkedin.com");
}

function normalizeJobUrl(
  value: string | null,
  baseUrl: string,
): { url: string; externalId: string | null } | null {
  if (value === null || value.length > 2000) return null;
  let url: URL;
  try {
    url = new URL(value, baseUrl);
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || !isLinkedInHost(url.hostname)) return null;
  if (!url.pathname.toLowerCase().includes("/jobs/view/")) return null;
  const id = jobId(url.pathname);
  if (id !== null) {
    return { url: `https://www.linkedin.com/jobs/view/${id}`, externalId: id };
  }
  url.username = "";
  url.password = "";
  url.search = "";
  url.hash = "";
  return { url: url.toString(), externalId: null };
}

function jobId(pathname: string): string | null {
  const segment = pathname.split("/").filter(Boolean).at(-1) ?? "";
  const match = segment.match(/(?:^|-)(\d{5,20})$/);
  return match?.[1] ?? null;
}

function bounded(value: string | null, min: number, max: number): string | null {
  if (value === null || /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/.test(value)) {
    return null;
  }
  const normalized = value.replace(/\s+/g, " ").trim();
  return normalized.length >= min && normalized.length <= max ? normalized : null;
}
