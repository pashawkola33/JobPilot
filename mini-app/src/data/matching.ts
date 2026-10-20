import { captureProfileCredential, invalidateAccount } from './account';
import { JobPilotError, type Job, type WorkflowStatus } from './types';

export interface MatchState { activeProfileRevision: number; status: 'NOT_READY' | 'PROCESSING' | 'READY' | 'FAILED'; extractorVersion: string; scorerVersion: string; publicationRevision: number; catalogueRevision: number; processingEnabled: boolean }
export interface MatchItem {
  vacancy: { id: number; title: string; company: string; location: string; workFormat: Job['remoteType']; employmentType: string; canonicalUrl: string; source: string; publishedAt: string; workflowStatus: WorkflowStatus };
  match: { eligibility: 'ELIGIBLE' | 'INELIGIBLE' | 'UNKNOWN'; compatibilityScore: number; recommendationScore: number | null; evidenceCoverage: number; factors: Record<string, number>; reasons: { code: string; dimension: string; status: string; explanation: string; evidence: string; candidateField: string }[] };
  computation: { searchProfileVersion: number; jobRevision: number; extractorVersion: string; scorerVersion: string; computedAt: string };
}
export interface MatchPage { items: MatchItem[]; nextCursor: string | null; state: MatchState }
export class MatchPageStale extends Error {}
const percentage = (value: unknown) => typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 100;
function valid(item: MatchItem, state: MatchState): boolean {
  return !!item?.vacancy && Number.isSafeInteger(item.vacancy.id) && item.vacancy.id > 0 && typeof item.vacancy.title === 'string'
    && !!item.match && ['ELIGIBLE', 'INELIGIBLE', 'UNKNOWN'].includes(item.match.eligibility)
    && percentage(item.match.compatibilityScore) && percentage(item.match.evidenceCoverage)
    && (item.match.eligibility === 'INELIGIBLE' ? item.match.recommendationScore === null : item.match.recommendationScore === item.match.compatibilityScore)
    && Array.isArray(item.match.reasons) && item.match.reasons.length <= 256
    && item.match.reasons.every(reason => typeof reason.code === 'string' && typeof reason.explanation === 'string' && typeof reason.evidence === 'string')
    && !!item.computation && item.computation.searchProfileVersion === state.activeProfileRevision
    && item.computation.extractorVersion === state.extractorVersion && item.computation.scorerVersion === state.scorerVersion;
}
export function createMatchingClient() {
  const credential = captureProfileCredential();
  async function read(path: string) {
    let response: Response;
    try { response = await fetch(`/api/mini-app/v2${path}`, { headers: credential.headers(), credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(10_000) }); }
    catch { credential.headers(); throw new JobPilotError('unavailable'); }
    credential.headers();
    if (response.status === 401 || response.status === 403) { invalidateAccount(response.status === 403 ? 'forbidden' : 'expired'); throw new JobPilotError('expired'); }
    if (response.status === 409) throw new MatchPageStale();
    if (!response.ok) throw new JobPilotError('unavailable');
    const body = await response.json().catch(() => null); credential.headers();
    if (!body || !body.state || !['NOT_READY', 'PROCESSING', 'READY', 'FAILED'].includes(body.state.status)) throw new JobPilotError('unavailable');
    return body;
  }
  return {
    async list(cursor?: string, filter = 'RECOMMENDED'): Promise<MatchPage> {
      const query = new URLSearchParams({ limit: '25', eligibility: filter }); if (cursor) query.set('cursor', cursor);
      const body = await read(`/matches?${query}`);
      if (!Array.isArray(body.items) || body.items.length > 50 || !body.items.every((item: MatchItem) => valid(item, body.state))
          || body.nextCursor !== null && (typeof body.nextCursor !== 'string' || body.nextCursor.length > 2048)) throw new JobPilotError('unavailable');
      return body as MatchPage;
    },
    async detail(id: number): Promise<{ vacancy: MatchItem['vacancy']; facts: { description: string } | null; match: MatchItem['match'] | null; computation: MatchItem['computation'] | null; state: MatchState }> {
      const body = await read(`/jobs/${id}`);
      if (body.vacancy?.id !== id || body.match !== null && !valid(body, body.state)) throw new JobPilotError('unavailable');
      return body;
    },
  };
}
/** Workflow transport fields stay neutral; this object never supplies a displayed match score. */
export function workflowJob(item: MatchItem): Job {
  const v = item.vacancy;
  return { id: v.id, title: v.title, company: v.company, location: v.location, remoteType: v.workFormat, employmentType: v.employmentType,
    source: v.source, publishedAt: v.publishedAt, canonicalUrl: v.canonicalUrl, workflowStatus: v.workflowStatus,
    score: 0, band: null, disposition: 'REVIEW', seniority: 'UNKNOWN', strengths: [], risks: [], requirements: [], activity: [], matchSummary: null };
}
