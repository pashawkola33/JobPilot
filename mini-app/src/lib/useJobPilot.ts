import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { repository } from '../data/repository';
import type {
  Application,
  ApplicationCounts,
  FailureKind,
  Job,
  MutationOutcome,
  ReviewStats,
  Snapshot,
  WorkflowStatus,
} from '../data/types';
import { createReadPipeline } from './readPipeline';
import {
  createJobQueue,
  failureKind,
  isAmbiguous,
  sendWithRecovery,
  type JobState,
  IDLE,
} from './jobMutations';
import { haptic } from './telegram';

export const UNDO_MS = 6000;

const EMPTY_STATS: ReviewStats = {
  unreviewedMatch: 0,
  unreviewedReview: 0,
  saved: 0,
  applied: 0,
  dismissed: 0,
};

const EMPTY_APPLICATION_COUNTS: ApplicationCounts = {
  total: 0,
  saved: 0,
  applied: 0,
  interview: 0,
  offer: 0,
  rejected: 0,
  withdrawn: 0,
};

/**
 * A live, server-issued reversal capability. The client stores the token and nothing else: it
 * never records what to restore, because only the server knows whether the tracking row it would
 * remove pre-existed the action or was created by it.
 */
export interface UndoEntry {
  jobId: number;
  title: string;
  /** "Saved", "Skipped", "Marked as applied" — the verb already in the past tense. */
  action: string;
  undoToken: string;
}

type Phase = 'loading' | 'ready' | 'error';

const newMutationId = () =>
  globalThis.crypto?.randomUUID?.() ?? `m-${Date.now()}-${Math.random().toString(36).slice(2)}`;

/**
 * Two kinds of state, deliberately not mixed.
 *
 * **Global** — the Review, Saved and Applications projections and their counts. Written *only*
 * by the read pipeline. A mutation reply never touches them, whatever revision it carries,
 * because its transaction may have read the world before an ingestion or Telegram write that a
 * concurrent read already returned.
 *
 * **Per job** — phase, live undo token, last error. Written from the operation result for the
 * one job that mutation held locked. Same-job actions serialize; different jobs never block each
 * other.
 *
 * The two connect in one direction: a settled mutation *requests* reconciliation, and never
 * writes global state itself.
 */
export function useJobPilot() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [failure, setFailure] = useState<FailureKind>('unavailable');
  /** A mutation the server refused, surfaced without discarding the queue. */
  const [writeFailure, setWriteFailure] = useState<FailureKind | null>(null);
  const [reviewJobs, setReviewJobs] = useState<Job[]>([]);
  const [reviewTotal, setReviewTotal] = useState(0);
  const [reviewLimit, setReviewLimit] = useState(0);
  const [reviewTruncated, setReviewTruncated] = useState(false);
  const [reviewLoadedCount, setReviewLoadedCount] = useState(0);
  const [savedJobs, setSavedJobs] = useState<Job[]>([]);
  const [savedTotal, setSavedTotal] = useState(0);
  const [savedTruncated, setSavedTruncated] = useState(false);
  const [applications, setApplications] = useState<Application[]>([]);
  const [applicationJobs, setApplicationJobs] = useState<Job[]>([]);
  const [applicationTotal, setApplicationTotal] = useState(0);
  const [applicationsTruncated, setApplicationsTruncated] = useState(false);
  const [stats, setStats] = useState<ReviewStats>(EMPTY_STATS);
  const [applicationCounts, setApplicationCounts] =
    useState<ApplicationCounts>(EMPTY_APPLICATION_COUNTS);
  /** Frozen at load: the queue must not reshuffle under the reviewer after each action. */
  const [queue, setQueue] = useState<number[]>([]);
  const [cursor, setCursor] = useState(0);
  /** 1 forward, -1 after an undo — the card transition travels the way the queue moved. */
  const [direction, setDirection] = useState(1);
  const [undo, setUndo] = useState<UndoEntry | null>(null);
  /** Per-job machine. A job here is either mid-write or in an honest unknown. */
  const [jobStates, setJobStates] = useState<Record<number, JobState>>({});
  const undoTimer = useRef<number | undefined>(undefined);
  const queueRef = useRef(createJobQueue());
  /** Set once the first read lands, so a mutation cannot be issued against nothing. */
  const resetQueueOnNextRead = useRef(true);

  const applySnapshot = useCallback((data: Snapshot) => {
    setReviewJobs(data.reviewQueue.items);
    setReviewTotal(data.reviewQueue.total);
    setSavedJobs(data.saved.items);
    setSavedTotal(data.saved.total);
    setSavedTruncated(data.saved.truncated);
    setApplications(data.applications.items);
    setApplicationJobs(data.applicationJobs);
    setApplicationTotal(data.applications.total);
    setApplicationsTruncated(data.applications.truncated);
    setStats(data.workflowCounts);
    setApplicationCounts(data.applicationCounts);
    if (resetQueueOnNextRead.current) {
      resetQueueOnNextRead.current = false;
      setReviewLimit(data.reviewQueue.limit);
      setReviewTruncated(data.reviewQueue.truncated);
      setReviewLoadedCount(data.reviewQueue.items.length);
      setQueue(data.reviewQueue.items.map((job) => job.id));
      setCursor(0);
    }
    setPhase('ready');
  }, []);

  const pipeline = useMemo(
    () => createReadPipeline(
      () => repository.load(),
      applySnapshot,
      (error: unknown) => {
        // Only a read that has never succeeded can take the whole app to the error screen.
        setPhase((existing) => {
          if (existing === 'ready') {
            setWriteFailure(failureKind(error));
            return existing;
          }
          setFailure(failureKind(error));
          return 'error';
        });
      },
    ),
    [applySnapshot],
  );

  const load = useCallback(() => {
    resetQueueOnNextRead.current = true;
    setPhase('loading');
    setWriteFailure(null);
    void pipeline.reconcile();
  }, [pipeline]);

  useEffect(load, [load]);
  useEffect(() => () => clearTimeout(undoTimer.current), []);

  const setJob = useCallback((jobId: number, next: Partial<JobState>) => {
    setJobStates((all) => ({ ...all, [jobId]: { ...(all[jobId] ?? IDLE), ...next } }));
  }, []);

  /** Puts the reviewer back on a specific vacancy, used when its write did not settle cleanly. */
  const focusJob = useCallback((jobId: number) => {
    setQueue((all) => {
      const index = all.indexOf(jobId);
      if (index >= 0) {
        setDirection(-1);
        setCursor(index);
      }
      return all;
    });
  }, []);

  const armUndo = useCallback((entry: UndoEntry) => {
    clearTimeout(undoTimer.current);
    setUndo(entry);
    undoTimer.current = setTimeout(() => setUndo(null), UNDO_MS);
  }, []);

  const disarmUndo = useCallback(() => {
    clearTimeout(undoTimer.current);
    setUndo(null);
  }, []);

  /**
   * Applies an operation result to the one job it is authoritative for, then asks the read
   * pipeline for fresh global state. It never writes the lists itself.
   */
  const settle = useCallback((outcome: MutationOutcome, entry: UndoEntry | null) => {
    setJob(outcome.jobId, { phase: 'idle', undoToken: outcome.undoToken, error: null });
    const patch = (all: Job[]) =>
      all.map((job) => (job.id === outcome.jobId ? { ...job, workflowStatus: outcome.status } : job));
    setReviewJobs(patch);
    setSavedJobs(patch);
    if (entry && outcome.undoToken) armUndo({ ...entry, undoToken: outcome.undoToken });
    else if (entry) disarmUndo();
    void pipeline.reconcile();
  }, [armUndo, disarmUndo, pipeline, setJob]);

  const decide = useCallback(
    (job: Job, status: WorkflowStatus, action: string) => {
      // An unknown job accepts no further writes until it is resolved (I9).
      if (jobStates[job.id]?.phase === 'unknown') return;

      setReviewJobs((all) =>
        all.map((candidate) =>
          candidate.id === job.id ? { ...candidate, workflowStatus: status } : candidate,
        ),
      );
      setDirection(1);
      setCursor((index) => index + 1);
      // Any new action retires the toast for the previous one; the server decides whether this
      // action gets its own, and settle() arms it only if a live token came back.
      disarmUndo();
      haptic(status === 'DISMISSED' ? 'light' : 'success');
      setJob(job.id, { phase: 'mutating', error: null });

      const mutationId = newMutationId();
      const pending: UndoEntry = { jobId: job.id, title: job.title, action, undoToken: '' };
      void queueRef.current.run(job.id, () =>
        sendWithRecovery(() => repository.setWorkflowStatus(job.id, status, mutationId)).then(
          (outcome) => settle(outcome, pending),
          (error: unknown) => {
            // Ambiguous here means the mutation *and* its same-id resolution both failed, so
            // nothing is known: not the optimistic state, not a rollback. Say so (I9).
            const unknown = isAmbiguous(error);
            setJob(job.id, {
              phase: unknown ? 'unknown' : 'idle',
              undoToken: null,
              error: failureKind(error),
            });
            disarmUndo();
            setWriteFailure(failureKind(error));
            // The optimistic advance already moved past this vacancy. Leaving the reviewer on
            // the next one would show a warning about a job they can no longer see, and would
            // put the retry out of reach — so step back to the job the message is about.
            focusJob(job.id);
            // A refused write leaves durable state we have not seen; a lost one may or may not
            // have landed. Either way the lists are only trustworthy after a fresh read.
            void pipeline.reconcile();
          },
        ),
      );
    },
    [disarmUndo, focusJob, jobStates, pipeline, setJob, settle],
  );

  /** Reversal is a server operation; the client sends a capability, never a state. */
  const revert = useCallback(() => {
    if (!undo) return;
    const target = undo;
    disarmUndo();
    haptic('warning');
    setJob(target.jobId, { phase: 'mutating', error: null });
    setDirection(-1);
    setCursor((index) => Math.max(0, index - 1));

    const mutationId = newMutationId();
    void queueRef.current.run(target.jobId, () =>
      sendWithRecovery(() => repository.undo(mutationId, target.undoToken)).then(
        (outcome) => settle(outcome, null),
        (error: unknown) => {
          setJob(target.jobId, {
            phase: isAmbiguous(error) ? 'unknown' : 'idle',
            undoToken: null,
            error: failureKind(error),
          });
          setWriteFailure(failureKind(error));
          focusJob(target.jobId);
          void pipeline.reconcile();
        },
      ),
    );
  }, [disarmUndo, focusJob, pipeline, setJob, settle, undo]);

  /** The deterministic retry offered for a job in the unknown state. */
  const reconcileJob = useCallback((jobId: number) => {
    setJob(jobId, { phase: 'idle', error: null });
    setWriteFailure(null);
    void pipeline.reconcile();
  }, [pipeline, setJob]);

  const skipToNext = useCallback(() => {
    setDirection(1);
    setCursor((index) => index + 1);
  }, []);

  const jobs = mergeJobs(reviewJobs, savedJobs, applicationJobs);
  const current = jobs.find((job) => job.id === queue[cursor]) ?? null;

  return {
    phase,
    failure,
    writeFailure,
    dismissWriteFailure: () => setWriteFailure(null),
    jobs,
    reviewTotal,
    reviewLimit,
    reviewTruncated,
    reviewLoadedCount,
    savedJobs,
    savedTotal,
    savedTruncated,
    applications,
    applicationTotal,
    applicationsTruncated,
    applicationCounts,
    stats,
    current,
    /** 1-based position within the bounded queue window frozen at load. */
    position: Math.min(cursor + 1, queue.length),
    direction,
    undo,
    jobStates,
    /** Jobs whose durable state is unknown until a resolution or read confirms it. */
    unknownJobs: Object.entries(jobStates)
      .filter(([, state]) => state.phase === 'unknown')
      .map(([id]) => Number(id)),
    decide,
    revert,
    reconcileJob,
    skipToNext,
    dismissUndo: () => setUndo(null),
    reload: load,
  };
}

function mergeJobs(review: Job[], saved: Job[], applications: Job[]): Job[] {
  const byId = new Map<number, Job>();
  for (const job of [...review, ...saved, ...applications]) byId.set(job.id, job);
  return [...byId.values()];
}
