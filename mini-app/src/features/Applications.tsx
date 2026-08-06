import { useState } from 'react';
import { motion } from 'motion/react';
import type { Application, ApplicationStatus, Job } from '../data/types';
import { applicationLabel, shortDate, until } from '../lib/format';
import { IconApplications } from '../components/icons';
import { JobRow } from '../components/JobRow';
import { State } from '../components/States';

/** Order follows ApplicationTransitionPolicy: the path a role actually travels. */
const STAGES: ApplicationStatus[] = [
  'SAVED',
  'APPLIED',
  'INTERVIEW',
  'OFFER',
  'REJECTED',
  'WITHDRAWN',
];

/** Stages a role can still move forward from. */
const LIVE: ApplicationStatus[] = ['SAVED', 'APPLIED', 'INTERVIEW', 'OFFER'];

type Filter = ApplicationStatus | 'ALL';

export function Applications({
  applications,
  jobs,
  onDetails,
  onReview,
}: {
  applications: Application[];
  jobs: Job[];
  onDetails: (job: Job) => void;
  onReview: () => void;
}) {
  const [filter, setFilter] = useState<Filter>('ALL');

  const visible =
    filter === 'ALL' ? applications : applications.filter((a) => a.status === filter);
  // Unscored applications sort last within their stage rather than jumping to the top.
  const ordered = [...visible].sort(
    (a, b) =>
      STAGES.indexOf(a.status) - STAGES.indexOf(b.status) || (b.score ?? -1) - (a.score ?? -1),
  );

  return (
    <>
      <header className="topbar">
        <h1 className="topbar__title">Applications</h1>
        <span className="eyebrow num">{applications.length}</span>
      </header>

      {applications.length === 0 ? (
        <State
          mark={<IconApplications size={32} />}
          title="No applications tracked"
          text="Saving a vacancy or marking one as applied starts tracking it here."
          action={
            <button type="button" className="btn btn--primary" onClick={onReview}>
              Start reviewing
            </button>
          }
        />
      ) : (
        <>
          <div className="filter" role="group" aria-label="Filter by stage">
            {(['ALL', ...STAGES] as Filter[]).map((stage) => {
              const count =
                stage === 'ALL'
                  ? applications.length
                  : applications.filter((a) => a.status === stage).length;
              const active = filter === stage;
              return (
                <button
                  key={stage}
                  type="button"
                  className="filter__item"
                  aria-pressed={active}
                  onClick={() => setFilter(stage)}
                >
                  {stage === 'ALL' ? 'All' : applicationLabel(stage)}
                  <span className="count">{count}</span>
                  {active && (
                    <motion.span
                      layoutId="filter-underline"
                      className="filter__underline"
                      aria-hidden="true"
                    />
                  )}
                </button>
              );
            })}
          </div>

          <div className="scroll">
            {ordered.length === 0 ? (
              <State
                mark={<IconApplications size={32} />}
                title={`Nothing at ${applicationLabel(filter as ApplicationStatus).toLowerCase()}`}
                text="Applications appear here as they reach this stage."
                action={
                  <button type="button" className="btn btn--quiet" onClick={() => setFilter('ALL')}>
                    Show all stages
                  </button>
                }
              />
            ) : (
              <ul className="rows">
                {ordered.map((application) => {
                  const job = jobs.find((candidate) => candidate.id === application.jobId);
                  return (
                    <JobRow
                      key={application.jobId}
                      score={application.score}
                      title={application.title}
                      meta={`${application.company} · ${detail(application)}`}
                      trailing={
                        <span
                          className={
                            LIVE.includes(application.status)
                              ? 'row__status row__status--live'
                              : 'row__status'
                          }
                        >
                          {applicationLabel(application.status)}
                        </span>
                      }
                      onOpen={job ? () => onDetails(job) : undefined}
                    />
                  );
                })}
              </ul>
            )}
          </div>
        </>
      )}
    </>
  );
}

/** The most useful second line differs by stage: a due follow-up beats a stale date. */
function detail(application: Application): string {
  if (application.nextFollowUpDate) return `Follow up ${until(application.nextFollowUpDate).toLowerCase()}`;
  if (application.appliedAt) return `Applied ${shortDate(application.appliedAt)}`;
  return `Saved ${shortDate(application.updatedAt)}`;
}
