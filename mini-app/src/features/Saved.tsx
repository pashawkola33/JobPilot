import type { Job } from '../data/types';
import { IconSaved } from '../components/icons';
import { JobRow } from '../components/JobRow';
import { State } from '../components/States';

export function Saved({
  jobs,
  onDetails,
  onReview,
}: {
  jobs: Job[];
  onDetails: (job: Job) => void;
  onReview: () => void;
}) {
  const saved = jobs
    .filter((job) => job.workflowStatus === 'SAVED')
    .sort((a, b) => b.score - a.score);

  return (
    <>
      <header className="topbar">
        <h1 className="topbar__title">Saved</h1>
        <span className="eyebrow num">{saved.length}</span>
      </header>

      {saved.length === 0 ? (
        <State
          mark={<IconSaved size={32} />}
          title="Nothing saved yet"
          text="Vacancies you save while reviewing collect here, strongest match first."
          action={
            <button type="button" className="btn btn--primary" onClick={onReview}>
              Start reviewing
            </button>
          }
        />
      ) : (
        <div className="scroll">
          <ul className="rows">
            {saved.map((job) => (
              <JobRow
                key={job.id}
                score={job.score}
                title={job.title}
                meta={`${job.company} · ${job.location}`}
                onOpen={() => onDetails(job)}
              />
            ))}
          </ul>
        </div>
      )}
    </>
  );
}
