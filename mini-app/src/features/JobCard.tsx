import type { Job } from '../data/types';
import {
  age,
  remoteLabel,
  seniorityLabel,
  sourceLabel,
  workflowLabel,
} from '../lib/format';
import { ScoreRail } from '../components/ScoreRail';

/**
 * The vacancy as it reads on screen. Provider tenants, external ids and screening
 * codes stay in Job.diagnostics and never appear here.
 */
export function JobCard({ job, footer }: { job: Job; footer?: React.ReactNode }) {
  return (
    <>
      <header>
        <p className="job__origin eyebrow">
          <span>{sourceLabel(job.source)}</span>
          <span className="job__origin-dot" aria-hidden="true" />
          <span>{age(job.publishedAt)}</span>
          <span className="job__origin-dot" aria-hidden="true" />
          <span>{job.disposition === 'MATCH' ? 'Auto-matched' : 'Flagged'}</span>
        </p>
        <h2 className="title" style={{ marginTop: 'var(--s2)' }}>
          {job.title}
        </h2>
        <p className="job__company">{job.company}</p>
        {/* Only a decided vacancy earns a status line; "not reviewed" is the default
            state of everything in this queue and says nothing worth a row. */}
        {job.workflowStatus !== 'UNREVIEWED' && (
          <p className="job__state">{workflowLabel(job.workflowStatus)}</p>
        )}
      </header>

      <ScoreRail score={job.score} band={job.band} />

      {job.matchSummary !== null && <p className="prose">{job.matchSummary}</p>}

      <dl className="meta">
        <Cell label="Location" value={job.location} />
        <Cell label="Work mode" value={remoteLabel(job.remoteType)} />
        <Cell label="Level" value={seniorityLabel(job.seniority)} />
        <Cell label="Employment" value={job.employmentType ?? 'Not stated'} />
      </dl>

      <Points title="What fits" mark="+" tone="up" items={job.strengths} />
      <Points title="What to weigh" mark="–" tone="down" items={job.risks} />

      {footer}
    </>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div className="meta__cell">
      <dt className="eyebrow">{label}</dt>
      <dd className="meta__value" style={{ margin: 0 }}>
        {value}
      </dd>
    </div>
  );
}

/** Marks differ by glyph and heading, so the two lists are never told apart by colour. */
export function Points({
  title,
  mark,
  tone,
  items,
}: {
  title: string;
  mark: string;
  tone: 'up' | 'down';
  items: string[];
}) {
  if (items.length === 0) return null;
  return (
    <section>
      {title && <h3 className="block__title">{title}</h3>}
      <ul className="points">
        {items.map((item) => (
          <li key={item} className="points__item">
            <span className={`points__mark points__mark--${tone}`} aria-hidden="true">
              {mark}
            </span>
            <span>{item}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}
