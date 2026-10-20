import { captureProfileCredential, invalidateAccount } from './account';
import { JobPilotError } from './types';

export class SearchProfileValidationError extends Error {}

export type Choice = 'UNKNOWN' | 'YES' | 'NO';
export interface SearchProfile {
  desiredTitles: string[]; roleCategories: string[]; industries: string[];
  technicalSkills: string[]; nontechnicalSkills: string[];
  experienceLevel: 'UNKNOWN' | 'NO_PROFESSIONAL_EXPERIENCE' | 'ENTRY' | 'MID' | 'SENIOR' | 'LEAD';
  statedExperience: string; experienceYears: number | null;
  currentCountry: string; currentCity: string; targetRegions: string[];
  workFormats: ('ONSITE' | 'HYBRID' | 'REMOTE')[];
  employmentTypes: ('FULL_TIME' | 'PART_TIME' | 'INTERNSHIP' | 'CONTRACT' | 'TEMPORARY' | 'APPRENTICESHIP' | 'VOLUNTEER' | 'OTHER')[];
  languages: { language: string; proficiency: 'UNKNOWN' | 'A1' | 'A2' | 'B1' | 'B2' | 'C1' | 'C2' | 'NATIVE' }[];
  workAuthorization: 'UNKNOWN' | 'AUTHORIZED' | 'REQUIRES_SPONSORSHIP' | 'NOT_AUTHORIZED';
  authorizationDetails: string;
  authorizationByCountry?: { country: string; status: SearchProfile['workAuthorization'] }[];
  salary: { amount: number; currency: string; period: 'HOUR' | 'DAY' | 'WEEK' | 'MONTH' | 'YEAR'; basis?: 'UNKNOWN' | 'GROSS' | 'NET' } | null;
  education: string; studentStatus: 'UNKNOWN' | 'STUDENT' | 'NOT_STUDENT'; relocation: Choice;
  additionalPreferences: string;
}
export interface SearchProfileView {
  activeRevision: number; profile: SearchProfile;
  completionStatus: 'NOT_STARTED' | 'INCOMPLETE' | 'COMPLETE';
  missingRequiredFields: string[]; createdAt: string | null;
}
export interface SearchProfileUpdate { expectedRevision: number; requestId: string; profile: SearchProfile }

/** Both issuing and consuming the response must belong to this exact authenticated launch. */
export function createSearchProfileClient() {
  const credential = captureProfileCredential();
  async function request(update?: SearchProfileUpdate): Promise<SearchProfileView> {
    let response: Response;
    try {
      response = await fetch('/api/mini-app/v2/me/search-profile', {
        method: update ? 'PUT' : 'GET', headers: { ...credential.headers(), ...(update ? { 'Content-Type': 'application/json' } : {}) },
        ...(update ? { body: JSON.stringify(update) } : {}), credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(10_000),
      });
    } catch { credential.headers(); throw new JobPilotError('unavailable'); }
    credential.headers();
    if (response.status === 401 || response.status === 403) {
      invalidateAccount(response.status === 403 ? 'forbidden' : 'expired'); throw new JobPilotError('expired');
    }
    if (response.status === 400) throw new SearchProfileValidationError();
    if (!response.ok) throw new JobPilotError(response.status === 409 ? 'conflict' : 'unavailable');
    const body = await response.json().catch(() => null);
    credential.headers();
    if (!body || !Number.isSafeInteger(body.activeRevision) || body.activeRevision < 0 || !body.profile
        || !['NOT_STARTED', 'INCOMPLETE', 'COMPLETE'].includes(body.completionStatus)
        || !Array.isArray(body.missingRequiredFields) || !Array.isArray(body.profile.desiredTitles)
        || !Array.isArray(body.profile.languages) || !Array.isArray(body.profile.workFormats)
        || (body.activeRevision > 0 && (typeof body.createdAt !== 'string' || !Number.isFinite(Date.parse(body.createdAt))))) {
      throw new JobPilotError('unavailable');
    }
    return body as SearchProfileView;
  }
  return { get: () => request(), save: (update: SearchProfileUpdate) => request(update) };
}
