import type { Snapshot } from '../data/types';

/**
 * The one path that may replace global state.
 *
 * Mutation replies are authoritative about their own job and carry no global projection, so
 * Review, Saved, Applications and the counts can only ever come from here. Two rules make that
 * safe:
 *
 * **Single flight.** At most one read is in the air. Extra callers coalesce onto it, and a
 * request that arrives while one is running schedules exactly one follow-up rather than one per
 * caller — so N settling mutations cause at most two reads, not N, and repeated requests cannot
 * starve the pipeline by continuously restarting it.
 *
 * **Read generation.** Every read takes an increasing generation and applies its result only if
 * no later generation has already been applied. Single flight alone makes overlap rare; the
 * generation makes "an older read can never overwrite a newer one" a property of this file
 * rather than an accident of promise ordering that a later edit could quietly break.
 *
 * Note what is *not* used for ordering: `snapshot.mutationRevision`. Out-of-band writers change
 * the read model without advancing it, so two genuinely different snapshots can carry the same
 * revision — comparing it would drop real changes and is the mistake this design exists to
 * avoid.
 */
export interface ReadPipeline {
  /** Requests reconciliation. Resolves when a read that started no earlier has been applied. */
  reconcile(): Promise<void>;
  /** Generation of the most recently applied read; 0 before the first one lands. */
  appliedGeneration(): number;
}

export function createReadPipeline(
  load: () => Promise<Snapshot>,
  apply: (snapshot: Snapshot, generation: number) => void,
  onError: (error: unknown) => void,
): ReadPipeline {
  let issued = 0;
  let applied = 0;
  let inFlight: Promise<void> | null = null;
  let followUpQueued = false;

  const run = (): Promise<void> => {
    const generation = ++issued;
    return load().then(
      (snapshot) => {
        // A read that lost its race against a later one is dropped, never applied.
        if (generation <= applied) return;
        applied = generation;
        apply(snapshot, generation);
      },
      (error: unknown) => onError(error),
    );
  };

  const start = (): Promise<void> => {
    inFlight = run().finally(() => {
      inFlight = null;
      if (followUpQueued) {
        followUpQueued = false;
        void start();
      }
    });
    return inFlight;
  };

  return {
    reconcile() {
      if (inFlight) {
        // Coalesce: whatever prompted this needs a read that starts after now, and one
        // follow-up covers every caller that asked while this one was running.
        followUpQueued = true;
        return inFlight;
      }
      return start();
    },
    appliedGeneration: () => applied,
  };
}
