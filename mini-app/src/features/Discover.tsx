import type { Application, Job, ReviewStats } from '../data/types';
import { applicationLabel, remoteLabel } from '../lib/format';
import { IconChevronRight } from '../components/icons';
import { JobRow } from '../components/JobRow';

/** Stages worth tracking as progress. Rejected and withdrawn are counted, not charted. */
const STAGES = ['SAVED', 'APPLIED', 'INTERVIEW', 'OFFER'] as const;

export function Discover({
  stats,
  jobs,
  applications,
  onReview,
  onDetails,
  onApplications,
}: {
  stats: ReviewStats;
  jobs: Job[];
  applications: Application[];
  onReview: () => void;
  onDetails: (job: Job) => void;
  onApplications: () => void;
}) {
  const waiting = stats.unreviewedMatch + stats.unreviewedReview;
  const strongest = jobs
    .filter((job) => job.workflowStatus === 'UNREVIEWED')
    .sort((a, b) => b.score - a.score)
    .slice(0, 3);

  const counts = STAGES.map((stage) => ({
    stage,
    count: applications.filter((a) => a.status === stage).length,
  }));
  const tracked = counts.reduce((sum, entry) => sum + entry.count, 0);

  return (
    <>
      <header className="topbar">
        <h1 className="topbar__title">JobPilot</h1>
      </header>

      <div className="scroll">
        <section className="lede">
          {/* The count leads the sentence rather than sitting above it as a stat tile. */}
          <h2 className="lede__line">
            <span className="lede__count">{waiting}</span>
            {waiting === 1 ? 'vacancy waiting' : 'vacancies waiting'}
          </h2>
          <p className="lede__text">
            {waiting === 0
              ? 'Your queue is clear. New vacancies arrive as sources are fetched.'
              : 'Screened, scored, ready to decide.'}
          </p>
          {waiting > 0 && (
            <p className="eyebrow lede__split">
              {stats.unreviewedMatch} auto-matched · {stats.unreviewedReview} flagged for review
            </p>
          )}
          <div className="lede__actions">
            <button type="button" className="btn btn--primary btn--wide" onClick={onReview}>
              {waiting === 0 ? 'Open review' : 'Continue review'}
            </button>
          </div>
        </section>

        {strongest.length > 0 && (
          <section>
            <div className="section-head">
              <h2 className="eyebrow">Strongest new matches</h2>
            </div>
            <ul className="rows">
              {strongest.map((job) => (
                <JobRow
                  key={job.id}
                  score={job.score}
                  title={job.title}
                  meta={`${job.company} · ${remoteLabel(job.remoteType)}`}
                  onOpen={() => onDetails(job)}
                />
              ))}
            </ul>
          </section>
        )}

        <section>
          <div className="section-head">
            <h2 className="eyebrow">Application progress</h2>
            <span className="eyebrow num">{stats.saved} saved</span>
          </div>
          <div className="progress">
            {tracked > 0 && (
              <div className="progress__bar" aria-hidden="true">
                {counts.map(({ stage, count }, index) =>
                  count === 0 ? null : (
                    <span
                      key={stage}
                      className="progress__slice"
                      style={{
                        width: `${(count / tracked) * 100}%`,
                        opacity: 1 - index * 0.22,
                      }}
                    />
                  ),
                )}
              </div>
            )}
            <ul className="progress__legend">
              {counts.map(({ stage, count }) => (
                <li key={stage} className="progress__key">
                  <span className="progress__value">{count}</span>
                  {applicationLabel(stage)}
                </li>
              ))}
            </ul>
            <button
              type="button"
              className="linkbtn"
              onClick={onApplications}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}
            >
              All applications
              <IconChevronRight size={14} />
            </button>
          </div>
        </section>
      </div>
    </>
  );
}
