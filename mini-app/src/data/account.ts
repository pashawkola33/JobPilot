import { available, initData } from '../lib/telegram';
import { JobPilotError, type FailureKind } from './types';

export interface Account {
  accountId: string;
  accountState: 'ONBOARDING_REQUIRED' | 'READY';
  onboardingRequired: boolean;
  legacyOwner: boolean;
  sessionExpiresAt: string;
}
export type AccountAccess = { kind: 'legacy' } | { kind: 'account'; account: Account; accessToken: string };
export const ACCOUNT_INVALIDATED = 'jobpilot:account-invalidated';
let launch = '';
let generation = 0;
let pending: Promise<AccountAccess> | null = null;
let access: AccountAccess | null = null;
let invalidReason: FailureKind | null = null;

export function invalidateAccount(kind: FailureKind = 'expired') {
  access = null;
  if (invalidReason) return;
  invalidReason = kind;
  window.dispatchEvent(new CustomEvent(ACCOUNT_INVALIDATED, { detail: kind }));
}

async function initialize(data: string, guard: () => void): Promise<AccountAccess> {
  let response: Response;
  try {
    response = await fetch('/api/mini-app/v2/auth/telegram', {
      method: 'POST', headers: { 'X-Telegram-Init-Data': data }, credentials: 'omit',
      cache: 'no-store', signal: AbortSignal.timeout(10_000),
    });
  } catch { guard(); throw new JobPilotError('unavailable'); }
  guard();
  const body = await response.json().catch(() => null);
  guard();
  // Only an explicit server-disabled response selects the owner-protected legacy API.
  if (response.status === 503 && body?.category === 'IDENTITY_DISABLED') return { kind: 'legacy' };
  if (!response.ok) throw new JobPilotError(response.status === 403 ? 'forbidden' : response.status === 401 ? 'invalid-auth' : 'expired');
  if (typeof body?.accessToken !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(body.accessToken)) throw new JobPilotError('invalid-auth');
  let me: Response;
  try {
    me = await fetch('/api/mini-app/v2/me', {
      headers: { Authorization: `Bearer ${body.accessToken}` }, credentials: 'omit', cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    });
  } catch { guard(); throw new JobPilotError('unavailable'); }
  guard();
  if (!me.ok) throw new JobPilotError(me.status === 403 ? 'forbidden' : 'expired');
  const account = await me.json().catch(() => null);
  guard();
  if (typeof account?.accountId !== 'string' || typeof account.legacyOwner !== 'boolean'
      || typeof account.onboardingRequired !== 'boolean' || !['ONBOARDING_REQUIRED', 'READY'].includes(account.accountState)
      || typeof account.sessionExpiresAt !== 'string' || !Number.isFinite(Date.parse(account.sessionExpiresAt))
      || Date.parse(account.sessionExpiresAt) <= Date.now()) throw new JobPilotError('expired');
  return { kind: 'account', account, accessToken: body.accessToken };
}

/** One exchange per launch, including React StrictMode remounts. Failed exchanges are never retried silently. */
export function initializeAccount(): Promise<AccountAccess> {
  const data = initData();
  if (!available) return Promise.reject(new JobPilotError('telegram-required'));
  if (!data) return Promise.reject(new JobPilotError('telegram-required'));
  if (data !== launch || !pending) {
    launch = data;
    access = null;
    invalidReason = null;
    const issued = ++generation;
    const guard = () => {
      if (issued !== generation || initData() !== data) throw new JobPilotError('expired');
    };
    pending = initialize(data, guard).then((result) => {
      guard();
      access = result;
      return result;
    });
  }
  return pending;
}

/** Immutable transport scope: retries can only use the session that mounted this workspace. */
export function captureWorkspaceCredential() {
  const issued = generation;
  const issuedLaunch = launch;
  const session = access;
  const beta = session?.kind === 'account' && !session.account.legacyOwner;
  return {
    base: beta ? '/api/mini-app/v2' : '/api/mini-app/v1',
    headers: (): Record<string, string> => {
      // Check the generation first: an old completion must never invalidate a new account.
      if (issued !== generation || issuedLaunch !== launch || initData() !== issuedLaunch) {
        throw new JobPilotError('expired');
      }
      if (!session || access !== session || invalidReason
          || session.kind === 'account' && Date.parse(session.account.sessionExpiresAt) <= Date.now()) {
        invalidateAccount(); throw new JobPilotError(invalidReason ?? 'expired');
      }
      return beta && session.kind === 'account'
        ? { Authorization: `Bearer ${session.accessToken}` }
        : { 'X-Telegram-Init-Data': issuedLaunch };
    },
  };
}

/** Profile APIs always use the v2 session, including for the optional legacy-owner editor. */
export function captureProfileCredential() {
  const workspace = captureWorkspaceCredential();
  const session = access;
  return { headers: (): Record<string, string> => {
    workspace.headers();
    if (session?.kind !== 'account') throw new JobPilotError('expired');
    return { Authorization: `Bearer ${session.accessToken}` };
  } };
}
