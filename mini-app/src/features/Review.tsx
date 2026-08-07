import { AnimatePresence, motion } from 'motion/react';
import type { FailureKind, Job, WorkflowStatus } from '../data/types';
import { FAILURE_MESSAGES } from '../data/types';
import type { UndoEntry } from '../lib/useJobPilot';
import { openLink } from '../lib/telegram';
import { IconChevronRight, IconEmpty, IconExternal } from '../components/icons';
import { State } from '../components/States';
import { UndoToast } from '../components/UndoToast';
import { JobCard } from './JobCard';

export function Review({
  job,
  position,
  loadedCount,
  remaining,
  limit,
  truncated,
  direction,
  undo,
  writeFailure,
  unknown,
  onDecide,
  onUndo,
  onDismissWriteFailure,
  onReconcile,
  onNext,
  onLoadNext,
  onDetails,
  onGoSaved,
}: {
  job: Job | null;
  position: number;
  loadedCount: number;
  remaining: number;
  limit: number;
  truncated: boolean;
  direction: number;
  undo: UndoEntry | null;
  writeFailure: FailureKind | null;
  /** This job's durable state could not be confirmed; no state may be presented as known. */
  unknown: boolean;
  onDecide: (job: Job, status: WorkflowStatus, action: string) => void;
  onUndo: () => void;
  onDismissWriteFailure: () => void;
  onReconcile: () => void;
  onNext: () => void;
  onLoadNext: () => void;
  onDetails: (job: Job) => void;
  onGoSaved: () => void;
}) {
  return (
    <div className="review">
      <header className="topbar">
        <h1 className="topbar__title">Review</h1>
        {job && (
          <span style={{ display: 'flex', alignItems: 'center', gap: 'var(--s3)' }}>
            <span
              className="eyebrow num"
              aria-label={`${position} of ${loadedCount} loaded; ${remaining} waiting`}
            >
              {position} of {loadedCount}
              {truncated && ` loaded · ${remaining} waiting`}
            </span>
            <button
              type="button"
              className="stepbtn"
              onClick={onNext}
              aria-label="Next vacancy without deciding"
            >
              Next
              <IconChevronRight size={15} />
            </button>
          </span>
        )}
      </header>

      {job ? (
        <div className="review__stage">
            <AnimatePresence initial={false} custom={direction}>
              <motion.article
                key={job.id}
                className="review__card"
                custom={direction}
                initial={{ opacity: 0, x: direction * 26 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: direction * -26 }}
                transition={{ duration: 0.24, ease: [0.22, 0.61, 0.36, 1] }}
              >
                <JobCard
                  job={job}
                  footer={
                    <button type="button" className="linkbtn" onClick={() => onDetails(job)}>
                      Full details
                    </button>
                  }
                />
              </motion.article>
            </AnimatePresence>
        </div>
      ) : (
        <State
          mark={<IconEmpty />}
          title={
            remaining > 0
              ? 'More vacancies are ready'
              : loadedCount === 0
                ? 'No vacancies to review'
                : 'Queue cleared'
          }
          text={
            remaining > 0
              ? `You reviewed the ${loadedCount} vacancies loaded in this batch. ${remaining} ${remaining === 1 ? 'vacancy is' : 'vacancies are'} still waiting. Load the next window of up to ${limit}.`
              : loadedCount === 0
              ? 'Nothing has been screened since your last session. New matches arrive as sources are fetched.'
              : 'You reviewed every vacancy in this batch. No vacancies are waiting.'
          }
          action={
            remaining > 0 ? (
              <button
                type="button"
                className="btn btn--primary"
                onClick={onLoadNext}
              >
                Load next batch
              </button>
            ) : (
              <button type="button" className="btn btn--quiet" onClick={onGoSaved}>
                Go to saved
              </button>
            )
          }
        />
      )}

      {/* The footer anchors the undo toast whether or not the action bar is present. */}
      <div className="review__foot">
        {unknown ? (
          // The mutation and its same-id resolution both failed, so the durable state is
          // genuinely unknown. Saying "not saved" would be a guess, and the optimistic state is
          // no safer a guess — so this claims neither and offers the retry instead (I9).
          <div className="toast" role="alert">
            <span className="toast__text">
              Not sure this saved
              <span className="toast__title">JobPilot could not confirm your change.</span>
            </span>
            <button type="button" className="toast__undo" onClick={onReconcile}>
              Check again
            </button>
          </div>
        ) : writeFailure ? (
          // A refused write is a definite answer from a server that was reached, so this one
          // may say the change did not land. role="alert" because it appears without asking.
          <div className="toast" role="alert">
            <span className="toast__text">
              {FAILURE_MESSAGES[writeFailure].title}
              <span className="toast__title">Your change was not saved.</span>
            </span>
            <button type="button" className="toast__undo" onClick={onDismissWriteFailure}>
              Dismiss
            </button>
          </div>
        ) : (
          <UndoToast undo={undo} onUndo={onUndo} />
        )}
        {job && (
          <div className="actions">
            <OpenVacancy url={job.canonicalUrl} />
            <div className="actions__row">
              <button
                type="button"
                className="btn btn--quiet"
                onClick={() => onDecide(job, 'DISMISSED', 'Skipped')}
              >
                Skip
              </button>
              <button
                type="button"
                className="btn btn--primary"
                onClick={() => onDecide(job, 'SAVED', 'Saved')}
              >
                Save
              </button>
              <button
                type="button"
                className="btn btn--quiet"
                onClick={() => onDecide(job, 'APPLIED', 'Marked as applied')}
              >
                Applied
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * A real anchor, so long-press, middle-click and copy-link all behave. Inside Telegram
 * the host opens the link instead; a missing link says so rather than failing silently.
 */
export function OpenVacancy({ url }: { url: string | null }) {
  if (!url) {
    return (
      <button type="button" className="btn btn--open btn--wide" disabled>
        No public link
        <span className="btn__hint">not captured at ingest</span>
      </button>
    );
  }
  return (
    <a
      className="btn btn--open btn--wide"
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      onClick={(event) => {
        if (openLink(url)) event.preventDefault();
      }}
    >
      Open vacancy
      <IconExternal />
    </a>
  );
}
