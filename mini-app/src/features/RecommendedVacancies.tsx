import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createMatchingClient, MatchPageStale, workflowJob, type MatchPage } from '../data/matching';
import type { useJobPilot } from '../lib/useJobPilot';
import { openLink } from '../lib/telegram';

export function RecommendedVacancies({ store }: { store: ReturnType<typeof useJobPilot> }) {
  const client = useMemo(() => createMatchingClient(), []);
  const [page, setPage] = useState<MatchPage | null>(null);
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [filter, setFilter] = useState('RECOMMENDED');
  const [details, setDetails] = useState<{ id: number; text: string } | null>(null);
  const issued = useRef(0); const live = useRef(true); const hadWrite = useRef(false);
  const load = useCallback(async (cursor?: string) => {
    const generation = ++issued.current; setBusy(true); setError('');
    try { const next = await client.list(cursor, filter); if (live.current && generation === issued.current) setPage(next); }
    catch (failure) { if (live.current && generation === issued.current) { setPage(null); setDetails(null); setError(failure instanceof MatchPageStale ? 'Recommendations changed. Reload to continue.' : 'Recommendations could not be loaded. Try again.'); } }
    finally { if (live.current && generation === issued.current) setBusy(false); }
  }, [client, filter]);
  useEffect(() => { live.current = true; void load(); return () => { live.current = false; issued.current += 1; }; }, [load]);
  const writing = Object.values(store.jobStates).some(value => value.phase === 'mutating');
  useEffect(() => { if (hadWrite.current && !writing) void load(); hadWrite.current = writing; }, [writing, load]);
  async function detail(id: number) {
    const generation = issued.current;
    try { const next = await client.detail(id); if (live.current && generation === issued.current) { setDetails({ id, text: next.facts?.description ?? 'Vacancy description is not available yet.' });
      if (!next.match) { setPage(null); setError('Matching needs recomputation. Reload for current results.'); } } }
    catch { if (live.current && generation === issued.current) setError('Vacancy details could not be loaded. Try again.'); }
  }
  return <section className="matching-workspace" aria-label="Personalized recommendations">
    <h2>Recommended vacancies</h2>
    <p>Deterministic compatibility with your saved, self-reported preferences; not a hiring probability.</p>
    <label>Show <select aria-label="Eligibility filter" value={filter} onChange={event => { setPage(null); setDetails(null); setFilter(event.target.value); }}>
      <option value="RECOMMENDED">Eligible and unknown</option><option value="ELIGIBLE">Eligible</option><option value="UNKNOWN">Unknown eligibility</option><option value="ALL">All, including ineligible</option>
    </select></label>
    {page?.state.status === 'PROCESSING' && <p role="status">{page.state.processingEnabled ? 'Matching is processing. Only valid current results are shown; more may arrive.' : 'Matching is waiting for processing. Only valid current results are shown.'}</p>}
    {page?.state.status === 'NOT_READY' && <p role="status">Complete your search preferences to start matching.</p>}
    {page?.state.status === 'FAILED' && <p role="alert">Matching needs attention before it can finish. Your saved preferences are preserved.</p>}
    {error && <p role="alert">{error}</p>}
    {busy && <p role="status">Loading recommendations…</p>}
    {page?.items.map(item => {
      const v = item.vacancy; const match = item.match; const job = store.jobs.find(value => value.id === v.id) ?? workflowJob(item);
      const blocked = store.unknownJobs.includes(v.id) || store.jobStates[v.id]?.phase === 'mutating';
      return <article className="beta-vacancy matching-vacancy" key={v.id}>
        <h3>{v.title}</h3><p>{v.company} · {v.location} · {v.workFormat}</p>
        <p className={`eligibility eligibility--${match.eligibility.toLowerCase()}`}>{match.eligibility === 'UNKNOWN' ? 'Eligibility unknown' : match.eligibility === 'INELIGIBLE' ? 'Ineligible' : 'Eligible for extracted requirements'}</p>
        {match.eligibility !== 'INELIGIBLE' && <p className="compatibility-score">{match.compatibilityScore}% compatibility</p>}
        <p>Evidence coverage: {match.evidenceCoverage}%</p>
        {match.eligibility === 'UNKNOWN' && <p>Requirements still need confirmation, even when compatibility is high.</p>}
        <ul>{match.reasons.filter(reason => reason.dimension !== 'SCORING').map((reason, index) => <li key={`${reason.code}-${index}`}>
          <strong>{reason.status === 'BLOCKED' ? 'Blocker: ' : reason.status === 'UNKNOWN' ? 'Unconfirmed: ' : reason.status === 'PREFERENCE' ? 'Ranking factor: ' : 'Satisfied: '}</strong>{reason.explanation}
          {reason.evidence && <blockquote>{reason.evidence}</blockquote>}
        </li>)}</ul>
        <details><summary>Compatibility breakdown</summary><p>Unstated or incomparable score factors use a neutral half-value. Coverage distinguishes these from established evidence.</p>
          <dl>{Object.entries(match.factors).map(([name, points]) => <div key={name}><dt>{name}</dt><dd>{points.toFixed(1)} points</dd></div>)}</dl></details>
        <div className="beta-actions">
          <a href={v.canonicalUrl} target="_blank" rel="noopener noreferrer" onClick={event => { if (openLink(v.canonicalUrl)) event.preventDefault(); }}>Open vacancy</a>
          <button className="btn btn--quiet" type="button" onClick={() => void detail(v.id)}>Vacancy details</button>
          {job.workflowStatus !== 'SAVED' && <button className="btn btn--primary" type="button" disabled={blocked} onClick={() => store.decide(job, 'SAVED', 'Saved', false)}>Save</button>}
          <button className="btn btn--quiet" type="button" disabled={blocked} onClick={() => store.decide(job, 'DISMISSED', 'Dismissed', false)}>Dismiss</button>
          <button className="btn btn--quiet" type="button" disabled={blocked} onClick={() => store.decide(job, 'APPLIED', 'Marked as applied', false)}>Mark applied</button>
        </div>
        {details?.id === v.id && <p className="matching-description">{details.text}</p>}
      </article>;
    })}
    {page && !page.items.length && page.state.status === 'READY' && <p>No current results for this filter.</p>}
    <div className="beta-actions"><button className="btn btn--quiet" type="button" disabled={busy} onClick={() => void load()}>Refresh recommendations</button>
      {page?.nextCursor && <button className="btn btn--primary" type="button" disabled={busy} onClick={() => void load(page.nextCursor!)}>Next recommendations</button>}</div>
  </section>;
}
