import { Fragment, useEffect, useState, type ReactNode } from 'react';
import { ACCOUNT_INVALIDATED, initializeAccount, invalidateAccount, type AccountAccess } from '../data/account';
import { mode } from '../data/repository';
import { FAILURE_MESSAGES, JobPilotError, type FailureKind } from '../data/types';
import { initData, ready } from '../lib/telegram';
import { IconAlert } from './icons';
import { State } from './States';
import { SearchProfileWorkspace } from '../features/SearchProfileWorkspace';

export function AccountGate({ children }: { children: ReactNode }) {
  return mode === 'api' ? <ApiAccountGate>{children}</ApiAccountGate> : children;
}

function ApiAccountGate({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<AccountAccess | null>(null);
  const [failure, setFailure] = useState<FailureKind | null>(null);
  const [epoch, setEpoch] = useState(0);
  useEffect(() => {
    ready();
    let alive = true;
    let requestEpoch = 0;
    let currentLaunch = initData();
    const load = () => {
      const issued = ++requestEpoch;
      setAccount(null); setFailure(null); setEpoch((value) => value + 1);
      initializeAccount().then(
        (value) => { if (alive && issued === requestEpoch) setAccount(value); },
        (error: unknown) => { if (alive && issued === requestEpoch) setFailure(error instanceof JobPilotError ? error.kind : 'unavailable'); },
      );
    };
    const changed = () => {
      if (initData() !== currentLaunch) { currentLaunch = initData(); load(); }
    };
    const invalidated = (event: Event) => {
      requestEpoch++;
      setAccount(null); setFailure((event as CustomEvent<FailureKind>).detail);
    };
    load();
    window.addEventListener('focus', changed);
    window.addEventListener('pageshow', changed);
    document.addEventListener('visibilitychange', changed);
    window.addEventListener(ACCOUNT_INVALIDATED, invalidated);
    return () => {
      alive = false;
      window.removeEventListener('focus', changed);
      window.removeEventListener('pageshow', changed);
      document.removeEventListener('visibilitychange', changed);
      window.removeEventListener(ACCOUNT_INVALIDATED, invalidated);
    };
  }, []);
  useEffect(() => {
    if (account?.kind !== 'account') return;
    const timer = window.setTimeout(() => invalidateAccount(), Math.max(0, Date.parse(account.account.sessionExpiresAt) - Date.now()));
    return () => window.clearTimeout(timer);
  }, [account]);
  if (failure) return <main className="app"><State mark={<IconAlert />} title={FAILURE_MESSAGES[failure].title}
    text={FAILURE_MESSAGES[failure].text} /><p className="bounded-note">Reopen JobPilot in Telegram</p></main>;
  if (!account) return <main className="app"><p role="status">Signing in…</p></main>;
  if (account.kind === 'account') {
    return <SearchProfileWorkspace key={epoch} legacyOwner={account.account.legacyOwner}>{children}</SearchProfileWorkspace>;
  }
  return <Fragment key={epoch}>{children}</Fragment>;
}
