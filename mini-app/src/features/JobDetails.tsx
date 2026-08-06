import { useEffect, useRef, useState } from 'react';
import type { Job } from '../data/types';
import { BAND_THRESHOLDS } from '../data/types';
import {
  age,
  remoteLabel,
  seniorityLabel,
  shortDate,
  sourceLabel,
  workflowLabel,
} from '../lib/format';
import { IconChevronDown, IconClose } from '../components/icons';
import { ScoreRail } from '../components/ScoreRail';
import { OpenVacancy } from './Review';
import { Points } from './JobCard';

/**
 * A native <dialog>: focus trapping, Esc, inert background and the backdrop all come
 * from the platform. Enter and exit are CSS transitions with allow-discrete, so no
 * animation library is involved in showing it.
 */
export function JobDetails({
  job,
  showDiagnostics,
  onClose,
}: {
  job: Job | null;
  showDiagnostics: boolean;
  onClose: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  /** Held through the closing transition so the sheet does not empty mid-fade. */
  const [shown, setShown] = useState<Job | null>(job);

  useEffect(() => {
    const element = dialog.current;
    if (!element) return;
    if (job) {
      setShown(job);
      if (!element.open) element.showModal();
    } else if (element.open) {
      element.close();
    }
  }, [job]);

  return (
    <dialog
      ref={dialog}
      className="sheet"
      aria-label={shown ? `${shown.title} at ${shown.company}` : 'Vacancy details'}
      onCancel={onClose}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === dialog.current) onClose();
      }}
    >
      {shown && (
        <>
          <span className="sheet__grip" aria-hidden="true" />
          <header className="sheet__head">
            <div style={{ flex: 1, minWidth: 0 }}>
              <p className="eyebrow">
                {sourceLabel(shown.source)} · {age(shown.publishedAt)}
              </p>
              <h2 className="title" style={{ fontSize: 19, marginTop: 4 }}>
                {shown.title}
              </h2>
              <p className="job__company">{shown.company}</p>
            </div>
            <button type="button" className="sheet__close" onClick={onClose} aria-label="Close details">
              <IconClose />
            </button>
          </header>

          <div className="sheet__body">
            <section>
              <ScoreRail score={shown.score} band={shown.band} />
              <dl className="meta" style={{ marginTop: 'var(--s3)' }}>
                <Cell label="Location" value={shown.location} />
                <Cell label="Work mode" value={remoteLabel(shown.remoteType)} />
                <Cell label="Level" value={seniorityLabel(shown.seniority)} />
                <Cell label="Employment" value={shown.employmentType ?? 'Not stated'} />
                <Cell label="Posted" value={shortDate(shown.publishedAt)} />
                <Cell label="Status" value={workflowLabel(shown.workflowStatus)} />
              </dl>
            </section>

            <section>
              <h3 className="block__title">Why this matched</h3>
              <p className="prose">{shown.matchSummary}</p>
              <p className="eyebrow" style={{ marginTop: 'var(--s3)', lineHeight: 1.5 }}>
                Bands — {BAND_THRESHOLDS[2]}+ excellent · {BAND_THRESHOLDS[1]}+ good ·{' '}
                {BAND_THRESHOLDS[0]}+ possible
              </p>
            </section>

            <div>
              <Disclosure title="What fits" count={shown.strengths.length} open>
                <Points title="" mark="+" tone="up" items={shown.strengths} />
              </Disclosure>

              <Disclosure title="What to weigh" count={shown.risks.length}>
                <Points title="" mark="–" tone="down" items={shown.risks} />
              </Disclosure>

              <Disclosure title="Requirements" count={shown.requirements.length}>
                <ul className="points">
                  {shown.requirements.map((requirement, index) => (
                    <li key={requirement} className="points__item">
                      <span className="points__mark points__mark--index" aria-hidden="true">
                        {index + 1}
                      </span>
                      <span>{requirement}</span>
                    </li>
                  ))}
                </ul>
              </Disclosure>

              <Disclosure title="Activity" count={shown.activity.length}>
                <ul className="timeline">
                  {[...shown.activity].reverse().map((entry) => (
                    <li key={entry.at + entry.label} className="timeline__item">
                      <span className="timeline__when">{shortDate(entry.at)}</span>
                      <span>{entry.label}</span>
                    </li>
                  ))}
                </ul>
              </Disclosure>

              {showDiagnostics && (
                <Disclosure title="Diagnostics" count={shown.diagnostics.screeningReasons.length}>
                  <pre className="diagnostics" style={{ padding: '0 0 var(--s3)' }}>
                    {`job ${shown.id}\ntenant ${shown.diagnostics.providerTenant}\nexternal ${shown.diagnostics.externalId}\n\n${shown.diagnostics.screeningReasons
                      .map((reason) => `${reason.stage} ${reason.code} — ${reason.message}`)
                      .join('\n')}`}
                  </pre>
                </Disclosure>
              )}
            </div>

            <OpenVacancy url={shown.canonicalUrl} />
          </div>
        </>
      )}
    </dialog>
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

function Disclosure({
  title,
  count,
  open,
  children,
}: {
  title: string;
  count: number;
  open?: boolean;
  children: React.ReactNode;
}) {
  if (count === 0) return null;
  return (
    <details className="disclose" open={open}>
      <summary className="disclose__summary">
        <span>
          {title}
          <span className="count">{count}</span>
        </span>
        <span className="disclose__chevron">
          <IconChevronDown />
        </span>
      </summary>
      <div className="disclose__content">{children}</div>
    </details>
  );
}
