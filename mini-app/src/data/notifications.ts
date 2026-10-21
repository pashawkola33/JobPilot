import { captureProfileCredential, invalidateAccount } from './account';
import { JobPilotError } from './types';

export interface NotificationPreferences {
  enabled: boolean; paused: boolean; immediate: boolean; digest: boolean;
  minimumScore: number; timezone: string; quietStart: string | null; quietEnd: string | null;
}
export interface NotificationView {
  revision: number; preferences: NotificationPreferences;
  connectionStatus: 'DISCONNECTED' | 'CONNECTED' | 'BLOCKED' | 'UNAVAILABLE' | 'LEGACY_MANAGED';
  pauseReasons: string[]; legacyManaged: boolean; eligibilityPolicy: 'ELIGIBLE_ONLY';
}
export interface NotificationUpdate extends NotificationPreferences { expectedRevision: number; requestId: string }
export interface NotificationActivation { activationUrl: string; expiresAt: string }
export class NotificationValidationError extends Error {}
const time = (value: unknown) => value === null || typeof value === 'string' && /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
export function validTimezone(value: string): boolean {
  try { return !!value && value.length <= 120 && !!new Intl.DateTimeFormat('en', { timeZone: value }).resolvedOptions().timeZone; }
  catch { return false; }
}
function validView(body: NotificationView): boolean {
  const p = body?.preferences;
  return !!body && Number.isSafeInteger(body.revision) && body.revision >= 0 && !!p
    && [p.enabled, p.paused, p.immediate, p.digest, body.legacyManaged].every(value => typeof value === 'boolean')
    && Number.isInteger(p.minimumScore) && p.minimumScore >= 0 && p.minimumScore <= 100
    && typeof p.timezone === 'string' && validTimezone(p.timezone)
    && time(p.quietStart) && time(p.quietEnd) && (p.quietStart === null) === (p.quietEnd === null)
    && (p.quietStart === null || p.quietStart !== p.quietEnd) && (!p.enabled || p.immediate || p.digest)
    && ['DISCONNECTED', 'CONNECTED', 'BLOCKED', 'UNAVAILABLE', 'LEGACY_MANAGED'].includes(body.connectionStatus)
    && Array.isArray(body.pauseReasons) && body.pauseReasons.length <= 32 && body.pauseReasons.every(value => typeof value === 'string' && value.length <= 80)
    && body.eligibilityPolicy === 'ELIGIBLE_ONLY';
}
function safeActivation(body: NotificationActivation): boolean {
  if (!body || typeof body.activationUrl !== 'string' || body.activationUrl.length > 256
      || typeof body.expiresAt !== 'string' || !Number.isFinite(Date.parse(body.expiresAt)) || Date.parse(body.expiresAt) <= Date.now()) return false;
  try {
    const url = new URL(body.activationUrl);
    return url.protocol === 'https:' && url.host === 't.me' && !url.username && !url.password && !url.hash
      && /^\/[A-Za-z][A-Za-z0-9_]{4,31}$/.test(url.pathname)
      && /^\?start=notify_[A-Za-z0-9_-]{43}$/.test(url.search);
  } catch { return false; }
}

/** A client belongs to exactly one authenticated launch, including every failure and body read. */
export function createNotificationClient() {
  const credential = captureProfileCredential();
  async function request(method: string, suffix = '', update?: NotificationUpdate) {
    let response: Response;
    try {
      response = await fetch(`/api/mini-app/v2/me/notifications${suffix}`, {
        method, headers: { ...credential.headers(), ...(update ? { 'Content-Type': 'application/json' } : {}) },
        ...(update ? { body: JSON.stringify(update) } : {}), credentials: 'omit', cache: 'no-store', signal: AbortSignal.timeout(10_000),
      });
    } catch { credential.headers(); throw new JobPilotError('unavailable'); }
    credential.headers();
    if (response.status === 401 || response.status === 403) {
      invalidateAccount(response.status === 403 ? 'forbidden' : 'expired'); throw new JobPilotError('expired');
    }
    if (response.status === 400) throw new NotificationValidationError();
    if (!response.ok) throw new JobPilotError(response.status === 409 ? 'conflict' : 'unavailable');
    const body = await response.json().catch(() => null);
    credential.headers();
    if (suffix === '/activation' ? !safeActivation(body) : !validView(body)) throw new JobPilotError('unavailable');
    return body;
  }
  return {
    get: (): Promise<NotificationView> => request('GET'),
    save: (update: NotificationUpdate): Promise<NotificationView> => request('PUT', '', update),
    activate: (): Promise<NotificationActivation> => request('POST', '/activation'),
    disconnect: (): Promise<NotificationView> => request('DELETE', '/connection'),
  };
}
