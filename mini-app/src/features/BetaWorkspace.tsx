import { RecommendedVacancies } from './RecommendedVacancies';
import { NotificationSettings } from './NotificationSettings';
import { useState } from 'react';
import { UndoToast } from '../components/UndoToast';
import { FAILURE_MESSAGES, type FailureKind, type Job } from '../data/types';
import { useJobPilot } from '../lib/useJobPilot';
import { openLink } from '../lib/telegram';

/** Shared vacancy facts and the current account's workflow only; no matching projection. */
export function BetaWorkspace() {
  const store = useJobPilot();
  const [tab, setTab] = useState<'catalogue' | 'saved' | 'applications' | 'recommended' | 'settings'>('catalogue');
  const jobs = tab === 'catalogue' ? (store.current ? [store.current] : []) : store.savedJobs;
  const retryable = (failure: FailureKind) => ['unavailable', 'not-found', 'conflict'].includes(failure);
  const vacancy = (job: Job) => {
    const blocked = store.unknownJobs.includes(job.id);
    const pending = store.jobStates[job.id]?.phase === 'mutating';
    return <article className="beta-vacancy" key={job.id}>
      <h2>{job.title}</h2><p>{job.company} · {job.location}</p>
      <p>{job.remoteType} · {job.employmentType ?? 'Employment type unstated'} · {job.source}</p>
      {job.canonicalUrl && <a href={job.canonicalUrl} target="_blank" rel="noopener noreferrer"
        onClick={(event) => { if (openLink(job.canonicalUrl!)) event.preventDefault(); }}>Open vacancy</a>}
      {blocked ? <p role="status">This change is not confirmed. Resolve it before making another change.</p> : null}
      <div className="beta-actions">
        {job.workflowStatus !== 'SAVED' && <button className="btn btn--primary" type="button" disabled={blocked || pending}
          onClick={() => store.decide(job, 'SAVED', 'Saved')}>Save</button>}
        <button className="btn btn--quiet" type="button" disabled={blocked || pending}
          onClick={() => store.decide(job, 'DISMISSED', 'Dismissed')}>Dismiss</button>
        <button className="btn btn--quiet" type="button" disabled={blocked || pending}
          onClick={() => store.decide(job, 'APPLIED', 'Marked as applied')}>Mark applied</button>
      </div>
    </article>;
  };
  return <main className="app beta-workspace">
    <header><h1>Shared vacancies</h1>
      <p>Your saves, dismissals and applications are private to this account.</p></header>
    <nav className="beta-actions" aria-label="Workspace">
      <button className="btn btn--quiet" type="button" aria-pressed={tab === 'recommended'} onClick={() => setTab('recommended')}>Recommended</button>
      <button className="btn btn--quiet" type="button" aria-pressed={tab === 'catalogue'} onClick={() => setTab('catalogue')}>Catalogue</button>
      <button className="btn btn--quiet" type="button" aria-pressed={tab === 'saved'} onClick={() => setTab('saved')}>Saved</button>
      <button className="btn btn--quiet" type="button" aria-pressed={tab === 'applications'} onClick={() => setTab('applications')}>Applications</button>
      <button className="btn btn--quiet" type="button" aria-pressed={tab === 'settings'} onClick={() => setTab('settings')}>Settings</button>
    </nav>
    {tab === 'settings' && <NotificationSettings />}
    {tab !== 'settings' && store.phase === 'loading' && <p role="status">Loading vacancies…</p>}
    {tab !== 'settings' && store.phase === 'error' && <section role="alert"><h2>{FAILURE_MESSAGES[store.failure].title}</h2>
      <p>{FAILURE_MESSAGES[store.failure].text}</p>
      {retryable(store.failure) && <button className="btn btn--quiet" type="button" onClick={store.reload}>Try again</button>}</section>}
    {tab !== 'settings' && store.phase === 'ready' && <>
      <p>Saved: {store.stats.saved} · Applied: {store.stats.applied} · Dismissed: {store.stats.dismissed}</p>
      {store.writeFailure && <section role="alert"><p>{FAILURE_MESSAGES[store.writeFailure].title}</p>
        <p>{FAILURE_MESSAGES[store.writeFailure].text}</p></section>}
      {store.unknownJobs.map((jobId) => <section role="status" key={jobId}>
        <p>{store.jobs.find((job) => job.id === jobId)?.title ?? 'Vacancy change'}: change not confirmed.</p>
        <button className="btn btn--quiet" type="button" disabled={store.recoveringJobs.includes(jobId)}
          onClick={() => store.recoverJob(jobId)}>{store.recoveringJobs.includes(jobId) ? 'Resolving…' : 'Resolve change'}</button>
      </section>)}
      {tab === 'recommended' ? <RecommendedVacancies store={store} /> : tab === 'applications' ? <>
        <h2>Your applications</h2>
        {store.applications.map((application) => <article className="beta-vacancy" key={application.jobId}>
          <h3>{application.title}</h3><p>{application.company}</p><p>{application.status}</p>
          {application.appliedAt && <p>Applied: {new Date(application.appliedAt).toLocaleDateString()}</p>}
          {application.nextFollowUpDate && <p>Follow up: {application.nextFollowUpDate}</p>}
        </article>)}
        {!store.applicationTotal && <p>No applications yet.</p>}
        {store.applicationsTruncated && <p>Showing {store.applications.length} of {store.applicationTotal} applications.</p>}
      </> : <>
        {jobs.map(vacancy)}
        {!jobs.length && <p>{tab === 'saved' ? 'No saved vacancies yet.' : 'No more vacancies in this window.'}</p>}
        {tab === 'saved' && store.savedTruncated && <p>Showing {jobs.length} of {store.savedTotal} saved vacancies.</p>}
        {tab === 'catalogue' && store.reviewTruncated && <p>This window contains up to {store.reviewLimit} vacancies of {store.reviewTotal} remaining.</p>}
      </>}
      <button className="btn btn--quiet" type="button" onClick={store.reload}>Refresh vacancies</button>
      <UndoToast undo={store.undo} onUndo={store.revert} />
    </>}
  </main>;
}
