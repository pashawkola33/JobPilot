import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createSearchProfileClient, type SearchProfileView } from '../data/searchProfile';
import { SearchProfileEditor } from './SearchProfileEditor';
import { BetaWorkspace } from './BetaWorkspace';

/** The owner opts in; beta completes explicit preferences before entering its neutral workspace. */
export function SearchProfileWorkspace({ legacyOwner, children }: { legacyOwner: boolean; children: ReactNode }) {
  const client = useMemo(() => createSearchProfileClient(), []);
  const firstLoad = useRef(true);
  const [editing, setEditing] = useState(!legacyOwner);
  const [view, setView] = useState<SearchProfileView | null>(null);
  const [failed, setFailed] = useState(false);
  const [load, setLoad] = useState(0);
  const [editorEpoch, setEditorEpoch] = useState(0);
  useEffect(() => {
    if (!editing) return;
    let alive = true; setFailed(false);
    client.get().then((next) => { if (alive) { setView(next); setEditorEpoch((v) => v + 1); if (firstLoad.current && !legacyOwner && next.completionStatus === 'COMPLETE') setEditing(false); firstLoad.current = false; } },
      () => { if (alive) setFailed(true); });
    return () => { alive = false; };
  }, [client, load, editing, legacyOwner]);
  // Opening an existing editor reloads confirmed state and then leaves it editable.
  const open = () => { setEditing(true); setView(null); setFailed(false); setLoad((v) => v + 1); };
  const workspace = legacyOwner ? children : <BetaWorkspace />;
  if (!editing) return <><button className="btn btn--quiet profile-entry" type="button" onClick={open}>Edit search preferences</button>{workspace}</>;
  if (failed) return <main className="app profile-screen"><h1>Search preferences unavailable</h1><p role="alert">Your profile could not be loaded. Try again to retrieve confirmed server state.</p>
    <button className="btn btn--primary" type="button" onClick={() => setLoad((v) => v + 1)}>Try again</button>
    {legacyOwner && <button className="btn btn--quiet" type="button" onClick={() => setEditing(false)}>Return to workspace</button>}</main>;
  if (!view) return <main className="app profile-screen"><p role="status">Loading your search preferences…</p></main>;
  return <SearchProfileEditor key={editorEpoch} initial={view} client={client}
    onReload={() => { setView(null); setLoad((v) => v + 1); }}
    onCancel={legacyOwner || view.completionStatus === 'COMPLETE' ? () => setEditing(false) : undefined}
    onSaved={(next) => { setView(next); if (legacyOwner || next.completionStatus === 'COMPLETE') setEditing(false); }} />;
}
