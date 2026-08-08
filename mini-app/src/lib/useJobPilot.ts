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
  isBlocked,
  sendFor,
  sendWithRecovery,
  type JobState,
  type RecoveryDescriptor,
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
  /** Lazily created once. Passing createJobQueue() to useRef would allocate on every render. */
  const jobQueue = useMemo(() => createJobQueue(), []);
  /** Set once the first read lands, so a mutation cannot be issued against nothing. */
  const resetQueueOnNextRead = useRef(true);
  /**
   * Mirrors state that failure handling needs to read. State updaters must stay pure — React
   * may call them twice — so branching on `phase` or `queue` inside one would double the side
   * effects it guards.
   */
  const hasLoaded = useRef(false);
  const queueIds = useRef<number[]>([]);
  /**
   * Recovery reads job state through this rather than through the render closure: the
   * re-entrancy guard must see the phase set by the press it is guarding against, which a
   * captured value from an earlier render would not.
   */
  const jobStatesRef = useRef<Record<number, JobState>>({});

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
    hasLoaded.current = true;
    if (resetQueueOnNextRead.current) {
      resetQueueOnNextRead.current = false;
      queueIds.current = data.reviewQueue.items.map((job) => job.id);
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
        // Only a read that has never succeeded can take the whole app to the error screen; a
        // later one that fails is a write-surface problem, not a dead app.
        if (hasLoaded.current) {
          setWriteFailure(failureKind(error));
          return;
        }
        setFailure(failureKind(error));
        setPhase('error');
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
    const merged = { ...(jobStatesRef.current[jobId] ?? IDLE), ...next };
    jobStatesRef.current = { ...jobStatesRef.current, [jobId]: merged };
    setJobStates(jobStatesRef.current);
  }, []);

  /** Puts the reviewer back on a specific vacancy, used when its write did not settle cleanly. */
  const focusJob = useCallback((jobId: number) => {
    const index = queueIds.current.indexOf(jobId);
    if (index < 0) return;
    setDirection(-1);
    setCursor(index);
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
    setJob(outcome.jobId, {
      phase: 'idle', undoToken: outcome.undoToken, error: null, recovery: null,
    });
    // A fresh result describes what just happened, so showing it immediately is the optimistic
    // UI for the user's own action. A *replayed* result describes what happened earlier, and an
    // out-of-band writer may have moved past it since — so it is never painted, and the read
    // pipeline supplies the affected job's status along with everything else.
    if (!outcome.replayed) {
      const patch = (all: Job[]) =>
        all.map((job) =>
          job.id === outcome.jobId ? { ...job, workflowStatus: outcome.status } : job);
      setReviewJobs(patch);
      setSavedJobs(patch);
    }
    if (entry && outcome.undoToken) armUndo({ ...entry, undoToken: outcome.undoToken });
    else if (entry) disarmUndo();
    void pipeline.reconcile();
  }, [armUndo, disarmUndo, pipeline, setJob]);

  const decide = useCallback(
    (job: Job, status: WorkflowStatus, action: string) => {
      // An unresolved job accepts no further writes until its own operation has a verdict (I9).
      if (isBlocked(jobStates[job.id])) return;

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

      // Written once here and never rewritten: every later attempt re-sends this exact identity.
      const recovery: RecoveryDescriptor = {
        kind: 'workflow', mutationId: newMutationId(), jobId: job.id, status,
      };
      const pending: UndoEntry = { jobId: job.id, title: job.title, action, undoToken: '' };
      void jobQueue.run(job.id, () =>
        sendWithRecovery(sendFor(repository, recovery)).then(
          (outcome) => settle(outcome, pending),
          (error: unknown) => {
            // Ambiguous here means the mutation *and* its same-id resolution both failed, so
            // nothing is known: not the optimistic state, not a rollback. Say so (I9).
            const unknown = isAmbiguous(error);
            setJob(job.id, {
              phase: unknown ? 'unknown' : 'idle',
              undoToken: null,
              error: failureKind(error),
              // Kept verbatim while unknown: resolving this later means re-asking about *this*
              // operation, which is the only question whose answer settles it.
              recovery: unknown ? recovery : null,
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
    [disarmUndo, focusJob, jobQueue, jobStates, pipeline, setJob, settle],
  );

  /** Reversal is a server operation; the client sends a capability, never a state. */
  const revert = useCallback(() => {
    if (!undo) return;
    const target = undo;
    // A job with an undecided operation takes no new writes, reversal included.
    if (isBlocked(jobStatesRef.current[target.jobId])) return;
    disarmUndo();
    haptic('warning');
    setJob(target.jobId, { phase: 'mutating', error: null });
    setDirection(-1);
    setCursor((index) => Math.max(0, index - 1));

    // Same rule as a forward mutation: the identity is fixed here and re-sent unchanged by
    // every later attempt, so a lost reversal is recoverable rather than a dead end.
    const recovery: RecoveryDescriptor = {
      kind: 'undo', mutationId: newMutationId(), jobId: target.jobId, undoToken: target.undoToken,
    };
    void jobQueue.run(target.jobId, () =>
      sendWithRecovery(sendFor(repository, recovery)).then(
        (outcome) => settle(outcome, null),
        (error: unknown) => {
          const unknown = isAmbiguous(error);
          setJob(target.jobId, {
            phase: unknown ? 'unknown' : 'idle',
            undoToken: null,
            error: failureKind(error),
            recovery: unknown ? recovery : null,
          });
          setWriteFailure(failureKind(error));
          focusJob(target.jobId);
          void pipeline.reconcile();
        },
      ),
    );
  }, [disarmUndo, focusJob, jobQueue, pipeline, setJob, settle, undo]);

  /**
   * The deterministic retry for an unresolved job — and the only thing that can clear its
   * unknown state.
   *
   * It re-sends the recorded operation, id and payload unchanged, because a snapshot read cannot
   * answer the question being asked. A GET is authoritative about its own database moment and
   * says nothing about whether the original PUT is about to commit a moment later, so clearing
   * unknown on a successful read would just be guessing with extra steps.
   *
   * The job stays blocked for the whole attempt, and a failed attempt keeps the descriptor so
   * the next press asks about the same operation again.
   */
  const recoverJob = useCallback((jobId: number) => {
    const state = jobStatesRef.current[jobId];
    // Re-entrancy guard: a second press while an attempt is in flight is a no-op, so a
    // double-click cannot start two resolutions of the same operation.
    if (!state || state.phase === 'recovering') return;

    /** Phase two: only a read that *applied* may release the job. */
    const authoritativeRead = () =>
      pipeline.reconcile().then((result) => {
        if (result === 'applied') {
          setJob(jobId, { phase: 'idle', error: null, recovery: null });
          return;
        }
        // The verdict stands but current state is still unseen, so the job stays blocked and
        // the next press re-reads without ever re-sending a mutation.
        setJob(jobId, { phase: 'awaitingRead', error: 'unavailable', recovery: null });
      });

    setJob(jobId, { phase: 'recovering', error: null });
    setWriteFailure(null);

    // The operation already has a terminal verdict: nothing to re-send, only to re-read.
    if (state.phase === 'awaitingRead' || !state.recovery) {
      void authoritativeRead();
      return;
    }

    const recovery = state.recovery;
    void jobQueue.run(jobId, () =>
      sendWithRecovery(sendFor(repository, recovery)).then(
        (outcome) => {
          // Phase one answered. The verdict may be a replay of a historical result, so it is
          // recorded as identity only — it never becomes global state — and the job stays
          // blocked until the read below confirms what is actually true now.
          setJob(jobId, { undoToken: outcome.undoToken, error: null, recovery: null });
          return authoritativeRead();
        },
        (error: unknown) => {
          setWriteFailure(failureKind(error));
          if (isAmbiguous(error)) {
            // Still no verdict, so the same descriptor is kept and the next press asks the same
            // question about the same operation.
            setJob(jobId, {
              phase: 'unknown', undoToken: null, error: failureKind(error), recovery,
            });
            void pipeline.reconcile();
            return;
          }
          // A definite refusal answers question one exactly as a success does, so it takes the
          // same second phase rather than a bare read whose result nobody looks at. Handing the
          // read to authoritativeRead() is what lets it release the job on *this* press.
          setJob(jobId, { undoToken: null, error: failureKind(error), recovery: null });
          return authoritativeRead();
        },
      ),
    );
  }, [jobQueue, pipeline, setJob]);

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
    /** Jobs with no confirmed durable state, until their own operation gets a verdict. */
    unknownJobs: Object.entries(jobStates)
      .filter(([, state]) => isBlocked(state))
      .map(([id]) => Number(id)),
    /** Jobs whose resolution is in flight, so the retry affordance is not offered twice. */
    recoveringJobs: Object.entries(jobStates)
      .filter(([, state]) => state.phase === 'recovering')
      .map(([id]) => Number(id)),
    decide,
    revert,
    recoverJob,
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
