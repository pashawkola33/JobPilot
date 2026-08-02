/**
 * Shared types for the JobPilot browser-rendering fallback worker.
 *
 * The worker renders ONE validated public vacancy URL and returns bounded typed
 * JSON. It never touches PostgreSQL, an LLM, Telegram, scoring, or applications.
 */

export const RESULT_STATUSES = [
  "EXTRACTED",
  "INSUFFICIENT_DATA",
  "UNSUPPORTED",
  "BLOCKED",
  "AUTH_REQUIRED",
  "CHALLENGE_DETECTED",
  "RATE_LIMITED",
  "TIMEOUT",
  "INVALID_URL",
  "FETCH_FAILED",
] as const;

export type ResultStatus = (typeof RESULT_STATUSES)[number];

export type TextEvidence = "JSON_LD" | "EMBEDDED" | "META" | "DOM";
export type DescriptionEvidence = TextEvidence;

export type DetailProvider = "GREENHOUSE" | "ASHBY" | "LEVER" | "RECRUITEE" | "LINKEDIN";

export interface ExtractedJob {
  externalId: string;
  title: string;
  sourceUrl: string;
  company: string | null;
  location: string | null;
  description: string | null;
  employmentType: string | null;
  workplaceType: string | null;
  publishedAt: string | null;
  applicationUrl: string | null;
  provider: DetailProvider | null;
  /** Compatibility alias consumed by the existing Java fallback DTO. */
  canonicalUrl: string;
}

export interface ExtractionEvidence {
  titleSource: TextEvidence;
  companySource: TextEvidence | null;
  descriptionSource: DescriptionEvidence | null;
}

export interface ExtractRequest {
  requestId: string;
  url: string;
  expectedSource?: string | undefined;
}

export interface ExtractSuccess {
  requestId: string;
  status: "EXTRACTED";
  finalUrl: string;
  sourceType: "BROWSER";
  job: ExtractedJob;
  evidence: ExtractionEvidence;
}

export interface SearchJob {
  externalId: string | null;
  title: string;
  company: string;
  location: string | null;
  url: string;
}

export interface SearchSuccess {
  requestId: string;
  status: "EXTRACTED";
  finalUrl: string;
  sourceType: "BROWSER";
  jobs: SearchJob[];
}

export interface ExtractFailure {
  requestId: string;
  status: Exclude<ResultStatus, "EXTRACTED">;
}

export type ExtractResponse = ExtractSuccess | ExtractFailure;
export type SearchResponse = SearchSuccess | ExtractFailure;

export interface RawSearchJob {
  title: string | null;
  company: string | null;
  location: string | null;
  url: string | null;
}

/**
 * Serializable data collected inside the rendered page. Deliberately small and
 * bounded: no HTML, cookies, storage, headers, or screenshots leave the page.
 */
export interface RawPageData {
  finalUrl: string;
  /** Raw text content of every <script type="application/ld+json"> block. */
  jsonLdBlocks: string[];
  /** Selected meta/OpenGraph values keyed by a fixed known name. */
  meta: Record<string, string>;
  /** Candidate embedded public job-state JSON strings (bounded). */
  embeddedState: string[];
  /** Bounded semantic DOM candidates. */
  dom: {
    externalId?: string | null;
    title: string | null;
    company: string | null;
    location: string | null;
    employmentType: string | null;
    workplaceType?: string | null;
    publishedAt?: string | null;
    applicationUrl?: string | null;
    provider?: DetailProvider | null;
    description: string | null;
  };
  /** Signals used only to classify login/challenge/blocked pages. */
  signals: {
    hasPasswordField: boolean;
    hasLoginForm: boolean;
    challengeMarker: boolean;
    accessDeniedTitle: boolean;
    bodyTextLength: number;
  };
  /** Public job cards, used only by the bounded LinkedIn search endpoint. */
  searchJobs?: RawSearchJob[];
}

/** Outcome of the browser rendering step (before field selection/validation). */
export type RenderOutcome =
  | { kind: "PAGE"; data: RawPageData }
  | { kind: "FAILURE"; status: Exclude<ResultStatus, "EXTRACTED"> };

/** Injected browser step so the HTTP layer is testable without a real browser. */
export type RenderFn = (request: ExtractRequest) => Promise<RenderOutcome>;
