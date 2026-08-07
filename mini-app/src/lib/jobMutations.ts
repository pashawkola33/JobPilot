import type { FailureKind, MutationOutcome } from '../data/types';
import { JobPilotError } from '../data/types';

/**
 * Per-job serialization and mutation-identity recovery.
 *
 * Two rules, and they are separate on purpose:
 *
 * **Same job serializes.** Operations on one job run in the order the user issued them, so
 * Save then Apply cannot settle as SAVED. **Different jobs do not.** There is no global pending
 * token — a slow write on one vacancy must never freeze the rest of the queue.
 *
 * **Ambiguity is resolved by identity, not by reading.** A timeout says the client stopped
 * waiting; it says nothing about whether the server transaction stopped. A recovery GET is
 * authoritative about its own moment and proves nothing about whether an in-flight mutation is
 * about to commit, so resolving one that way is right only by luck. Instead the same mutation id
 * and payload are re-sent: the server's ledger decides whether that means "here is what your
 * mutation did" or "nothing committed, so do it once now".
 */

/** A job whose durable state is genuinely unknown, and which therefore accepts no more writes. */
export type JobPhase = 'idle' | 'mutating' | 'unknown';

export interface JobState {
  phase: JobPhase;
  /** The live server-issued reversal capability for this job, if it currently has one. */
  undoToken: string | null;
  error: FailureKind | null;
}

export const IDLE: JobState = { phase: 'idle', undoToken: null, error: null };

/**
 * Only a transport failure is ambiguous. A typed refusal is a definite answer from a server that
 * was reached, so retrying it would just repeat a decision already made.
 */
export const isAmbiguous = (error: unknown): boolean =>
  !(error instanceof JobPilotError) || error.kind === 'unavailable';

export const failureKind = (error: unknown): FailureKind =>
  error instanceof JobPilotError ? error.kind : 'unavailable';

/**
 * Serializes work per job id without ever blocking a different one.
 *
 * A rejected task must not poison the chain: the stored link always swallows its rejection, or
 * one failed write would block that job forever. Settled chains are dropped so a long session
 * reviewing hundreds of vacancies does not accumulate a promise per job.
 */
export function createJobQueue() {
  const chains = new Map<number, Promise<unknown>>();

  return {
    run<T>(jobId: number, task: () => Promise<T>): Promise<T> {
      const previous = chains.get(jobId) ?? Promise.resolve();
      const result = previous.then(task, task);
      const link = result.catch(() => undefined);
      chains.set(jobId, link);
      void link.then(() => {
        // Only the newest link may clear the entry, or a slow task would delete a queue that
        // has since been extended.
        if (chains.get(jobId) === link) chains.delete(jobId);
      });
      return result;
    },
    /** Test seam: how many jobs still hold a queue. Must not grow without bound. */
    size: () => chains.size,
  };
}

/**
 * Sends one mutation, resolving an ambiguous delivery by re-sending the same identity.
 *
 * The retry is not a second mutation. The server either recognises the id and reports what the
 * original did, or finds nothing committed and executes exactly once — and while the original is
 * still running the duplicate queues behind it on the revision row rather than racing it.
 *
 * @throws the original error when the failure is definite, or the recovery error when the
 *   resolution attempt also fails — at which point the caller must treat the job as unknown.
 */
export async function sendWithRecovery(
  send: () => Promise<MutationOutcome>,
): Promise<MutationOutcome> {
  try {
    return await send();
  } catch (error: unknown) {
    if (!isAmbiguous(error)) throw error;
    // Same id, same payload. Never a bare GET: that cannot prove the first attempt failed.
    return await send();
  }
}
