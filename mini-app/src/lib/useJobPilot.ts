import { useCallback, useEffect, useRef, useState } from 'react';
import { repository } from '../data/repository';
import type {
  Application,
  ApplicationCounts,
  FailureKind,
  Job,
  ReviewStats,
  Snapshot,
  WorkflowStatus,
} from '../data/types';
import { JobPilotError } from '../data/types';
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

export interface UndoEntry {
  jobId: number;
  title: string;
  job: Job;
  /** "Saved", "Skipped", "Marked as applied" — the verb already in the past tense. */
  action: string;
  previous: WorkflowStatus;
  /** Recorded explicitly so P0-B never guesses whether a tracked application pre-dated Undo. */
  applicationExisted: boolean;
}

type Phase = 'loading' | 'ready' | 'error';

/** Any rejection that is not already typed is a backend we could not reach. */
const kindOf = (failure: unknown): FailureKind =>
  failure instanceof JobPilotError ? failure.kind : 'unavailable';

export function useJobPilot() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [failure, setFailure] = useState<FailureKind>('unavailable');
  /** A mutation that the server refused, surfaced without discarding the queue. */
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
  const undoTimer = useRef<number | undefined>(undefined);
  /** Identifies the decision whose write is still in flight; P0-B will make this per-job. */
  const pending = useRef<symbol | null>(null);

  const reconcile = useCallback((data: Snapshot, resetQueue: boolean) => {
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
    if (resetQueue) {
      setReviewLimit(data.reviewQueue.limit);
      setReviewTruncated(data.reviewQueue.truncated);
      setReviewLoadedCount(data.reviewQueue.items.length);
      setQueue(data.reviewQueue.items.map((job) => job.id));
      setCursor(0);
    }
  }, []);

  const load = useCallback(() => {
    setPhase('loading');
    setWriteFailure(null);
    repository.load().then(
      (data) => {
        reconcile(data, true);
        setPhase('ready');
      },
      (error: unknown) => {
        setFailure(kindOf(error));
        setPhase('error');
      },
    );
  }, [reconcile]);

  useEffect(load, [load]);
  useEffect(() => () => clearTimeout(undoTimer.current), []);

  const armUndo = useCallback((entry: UndoEntry) => {
    clearTimeout(undoTimer.current);
    setUndo(entry);
    undoTimer.current = setTimeout(() => setUndo(null), UNDO_MS);
  }, []);

  const disarmUndo = useCallback(() => {
    clearTimeout(undoTimer.current);
    setUndo(null);
  }, []);

  /** Undoing the optimistic queue edit; durable application rows are never deleted here. */
  const rollback = useCallback((entry: UndoEntry) => {
    const restored = { ...entry.job, workflowStatus: entry.previous };
    setReviewJobs((all) => {
      const without = all.filter((job) => job.id !== entry.jobId);
      return entry.previous === 'UNREVIEWED' ? [restored, ...without] : without;
    });
    setSavedJobs((all) => {
      const without = all.filter((job) => job.id !== entry.jobId);
      return entry.previous === 'SAVED' ? [restored, ...without] : without;
    });
    setDirection(-1);
    setCursor((index) => Math.max(0, index - 1));
  }, []);

  const decide = useCallback(
    (job: Job, status: WorkflowStatus, action: string) => {
      const entry: UndoEntry = {
        jobId: job.id,
        title: job.title,
        job,
        action,
        previous: job.workflowStatus,
        applicationExisted: applications.some((application) => application.jobId === job.id),
      };

      setReviewJobs((all) =>
        all.map((candidate) =>
          candidate.id === job.id ? { ...candidate, workflowStatus: status } : candidate,
        ),
      );
      setDirection(1);
      setCursor((index) => index + 1);
      // P0-A: Applied is not reversible. The application transition policy has no APPLIED to
      // SAVED edge, so an Undo here could only ever be rejected. Selecting Applied also drops
      // an Undo still armed for an earlier vacancy, which would otherwise reverse a decision
      // the user has already moved past. Deterministic Applied reversal belongs to P0-B.
      if (status === 'APPLIED') disarmUndo();
      else armUndo(entry);
      haptic(status === 'DISMISSED' ? 'light' : 'success');

      // P0-A keeps the existing single pending token. P0-B owns full mutation ordering.
      const token = Symbol('decision');
      pending.current = token;
      repository.setWorkflowStatus(job.id, status).then(
        (authoritative) => {
          if (pending.current !== token) return;
          pending.current = null;
          reconcile(authoritative, false);
        },
        (error: unknown) => {
          if (pending.current !== token) return;
          pending.current = null;
          clearTimeout(undoTimer.current);
          setUndo(null);
          rollback(entry);
          setWriteFailure(kindOf(error));
        },
      );
    },
    [applications, armUndo, disarmUndo, reconcile, rollback],
  );

  const revert = useCallback(() => {
    if (!undo) return;
    clearTimeout(undoTimer.current);
    // Claims the decision, so the original write's rejection becomes a no-op.
    pending.current = null;
    rollback(undo);
    setUndo(null);
    haptic('warning');
    repository.setWorkflowStatus(undo.jobId, undo.previous).then(
      (authoritative) => reconcile(authoritative, false),
      (error: unknown) => {
        // The optimistic rollback above is already on screen, so leaving it there would show a
        // state the server rejected. Re-read the authoritative snapshot first, then report the
        // failure, so the toast never accompanies a queue that disagrees with the server.
        const kind = kindOf(error);
        repository.load().then(
          (authoritative) => {
            reconcile(authoritative, false);
            setWriteFailure(kind);
          },
          () => setWriteFailure(kind),
        );
      },
    );
  }, [reconcile, rollback, undo]);

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
    decide,
    revert,
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
