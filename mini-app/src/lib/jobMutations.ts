import type {
  FailureKind,
  JobPilotRepository,
  MutationOutcome,
  WorkflowStatus,
} from '../data/types';
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

/**
 * A job whose durable state is genuinely unknown, and which therefore accepts no more writes.
 *
 * `recovering` is still unknown — it is unknown with a resolution in flight — so it blocks
 * writes exactly the same way. Separating them only lets the UI avoid offering a second
 * *Check again* while the first one is running.
 */
export type JobPhase = 'idle' | 'mutating' | 'unknown' | 'recovering';

/**
 * Everything needed to re-send an unresolved operation *as itself*.
 *
 * This is the whole point of the unknown state. A timeout says the client stopped waiting, not
 * that the server transaction stopped, so the only thing that can resolve it is the server's
 * verdict on this exact mutation id. Discarding the id and reading instead would ask a different
 * question — one whose answer cannot rule out the original committing a moment later.
 *
 * It is written once, when the operation is first issued, and never rewritten by a retry.
 */
export type RecoveryDescriptor =
  | { kind: 'workflow'; mutationId: string; jobId: number; status: WorkflowStatus }
  | { kind: 'undo'; mutationId: string; jobId: number; undoToken: string };

export interface JobState {
  phase: JobPhase;
  /** The live server-issued reversal capability for this job, if it currently has one. */
  undoToken: string | null;
  error: FailureKind | null;
  /** Present exactly while the job is unknown: the operation still awaiting a verdict. */
  recovery: RecoveryDescriptor | null;
}

export const IDLE: JobState = { phase: 'idle', undoToken: null, error: null, recovery: null };

/** Unknown and recovering both mean "no confirmed state", so both refuse further writes. */
export const isBlocked = (state: JobState | undefined): boolean =>
  state?.phase === 'unknown' || state?.phase === 'recovering';

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

/**
 * The only way an operation is sent — first attempt and every later recovery alike.
 *
 * Routing both through the descriptor is what makes "never mint a fresh id for a retry" a
 * property of the code rather than a rule someone has to remember: there is no send path that
 * takes a mutation id separately from the operation it identifies.
 */
export function sendFor(
  repository: JobPilotRepository,
  recovery: RecoveryDescriptor,
): () => Promise<MutationOutcome> {
  return recovery.kind === 'workflow'
    ? () => repository.setWorkflowStatus(recovery.jobId, recovery.status, recovery.mutationId)
    : () => repository.undo(recovery.mutationId, recovery.undoToken);
}
