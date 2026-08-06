import { useCallback, useEffect, useRef, useState } from 'react';
import { mockRepository } from '../data/repository';
import type { Application, Job, ReviewStats, WorkflowStatus } from '../data/types';
import { haptic } from './telegram';

export const UNDO_MS = 6000;

export interface UndoEntry {
  jobId: number;
  title: string;
  /** "Saved", "Skipped", "Marked as applied" — the verb already in the past tense. */
  action: string;
  previous: WorkflowStatus;
  /** True when the action also created the application row, so undo can remove it. */
  createdApplication: boolean;
}

type Phase = 'loading' | 'ready' | 'error';

export function useJobPilot() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [jobs, setJobs] = useState<Job[]>([]);
  const [applications, setApplications] = useState<Application[]>([]);
  /** Frozen at load: the queue must not reshuffle under the reviewer after each action. */
  const [queue, setQueue] = useState<number[]>([]);
  const [cursor, setCursor] = useState(0);
  /** 1 forward, -1 after an undo — the card transition travels the way the queue moved. */
  const [direction, setDirection] = useState(1);
  const [undo, setUndo] = useState<UndoEntry | null>(null);
  const undoTimer = useRef<number | undefined>(undefined);

  const load = useCallback(() => {
    setPhase('loading');
    mockRepository.load().then(
      (data) => {
        setJobs(data.jobs);
        setApplications(data.applications);
        setQueue(data.jobs.filter((j) => j.workflowStatus === 'UNREVIEWED').map((j) => j.id));
        setCursor(0);
        setPhase('ready');
      },
      () => setPhase('error'),
    );
  }, []);

  useEffect(load, [load]);
  useEffect(() => () => clearTimeout(undoTimer.current), []);

  const armUndo = useCallback((entry: UndoEntry) => {
    clearTimeout(undoTimer.current);
    setUndo(entry);
    undoTimer.current = setTimeout(() => setUndo(null), UNDO_MS);
  }, []);

  const decide = useCallback(
    (job: Job, status: WorkflowStatus, action: string) => {
      const tracked = applications.some((a) => a.jobId === job.id);
      const createdApplication = !tracked && status !== 'DISMISSED';

      setJobs((all) => all.map((j) => (j.id === job.id ? { ...j, workflowStatus: status } : j)));
      if (status !== 'DISMISSED') {
        setApplications((all) => upsert(all, job, status === 'APPLIED' ? 'APPLIED' : 'SAVED'));
      }
      setDirection(1);
      setCursor((index) => index + 1);
      armUndo({
        jobId: job.id,
        title: job.title,
        action,
        previous: job.workflowStatus,
        createdApplication,
      });
      haptic(status === 'DISMISSED' ? 'light' : 'success');
      void mockRepository.setWorkflowStatus(job.id, status);
    },
    [applications, armUndo],
  );

  const revert = useCallback(() => {
    if (!undo) return;
    clearTimeout(undoTimer.current);
    setJobs((all) =>
      all.map((j) => (j.id === undo.jobId ? { ...j, workflowStatus: undo.previous } : j)),
    );
    if (undo.createdApplication) {
      setApplications((all) => all.filter((a) => a.jobId !== undo.jobId));
    }
    setDirection(-1);
    setCursor((index) => Math.max(0, index - 1));
    setUndo(null);
    haptic('warning');
    void mockRepository.setWorkflowStatus(undo.jobId, undo.previous);
  }, [undo]);

  const skipToNext = useCallback(() => {
    setDirection(1);
    setCursor((index) => index + 1);
  }, []);

  const current = jobs.find((j) => j.id === queue[cursor]) ?? null;

  return {
    phase,
    jobs,
    applications,
    stats: reviewStats(jobs),
    current,
    /** 1-based position for display; total is the queue size frozen at load. */
    position: Math.min(cursor + 1, queue.length),
    total: queue.length,
    direction,
    undo,
    decide,
    revert,
    skipToNext,
    dismissUndo: () => setUndo(null),
    reload: load,
  };
}

/** Mirrors com.jobpilot.jobreview.application.JobReviewStats, derived rather than fetched. */
function reviewStats(jobs: Job[]): ReviewStats {
  const count = (predicate: (job: Job) => boolean) => jobs.filter(predicate).length;
  return {
    unreviewedMatch: count((j) => j.workflowStatus === 'UNREVIEWED' && j.disposition === 'MATCH'),
    unreviewedReview: count((j) => j.workflowStatus === 'UNREVIEWED' && j.disposition === 'REVIEW'),
    saved: count((j) => j.workflowStatus === 'SAVED'),
    applied: count((j) => j.workflowStatus === 'APPLIED'),
    dismissed: count((j) => j.workflowStatus === 'DISMISSED'),
  };
}

function upsert(all: Application[], job: Job, status: 'SAVED' | 'APPLIED'): Application[] {
  const now = new Date().toISOString();
  const existing = all.find((a) => a.jobId === job.id);
  const entry: Application = {
    jobId: job.id,
    title: job.title,
    company: job.company,
    status,
    canonicalUrl: job.canonicalUrl,
    score: job.score,
    updatedAt: now,
    appliedAt: status === 'APPLIED' ? now : (existing?.appliedAt ?? null),
    nextFollowUpDate: existing?.nextFollowUpDate ?? null,
  };
  return existing ? all.map((a) => (a.jobId === job.id ? entry : a)) : [entry, ...all];
}
