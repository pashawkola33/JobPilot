import type { Snapshot } from '../data/types';

/**
 * The one path that may replace global state.
 *
 * Mutation replies are authoritative about their own job and carry no global projection, so
 * Review, Saved, Applications and the counts can only ever come from here. Three rules make that
 * safe:
 *
 * **Single flight.** At most one read is in the air. Extra callers coalesce onto one follow-up
 * rather than one read each, so N settling mutations cause at most two reads, not N.
 *
 * **Read generation.** Every read takes an increasing generation and applies its result only if
 * no later generation has already been applied. Single flight alone makes overlap rare; the
 * generation makes "an older read can never overwrite a newer one" a property of this file
 * rather than an accident of promise ordering that a later edit could quietly break.
 *
 * **Requested-read semantics.** `reconcile()` resolves only once a read that started *after the
 * request* has been applied. Returning the in-flight read instead would hand callers a promise
 * that settles on data already stale when they asked — the caller would then act, correctly by
 * its own lights, on a snapshot that predates the change it was reconciling.
 *
 * Note what is *not* used for ordering: `snapshot.mutationRevision`. Out-of-band writers change
 * the read model without advancing it, so two genuinely different snapshots can carry the same
 * revision — comparing it would drop real changes and is the mistake this design exists to
 * avoid.
 */
/**
 * Whether the requested read actually became the global state.
 *
 * `'applied'` also covers a read whose body was superseded by a newer one that applied first —
 * the caller asked whether global state is now authoritative, and it is.
 */
export type ReadResult = 'applied' | 'failed';

export interface ReadPipeline {
  /**
   * Requests reconciliation.
   *
   * Resolves when a read that started no earlier than this call has settled, reporting whether
   * it applied. It never rejects: a failed read is a reported condition, not a caller's
   * exception to handle. Most callers ignore the result and simply want fresher state; the
   * recovery state machine needs it, because "the authoritative read succeeded" is precisely
   * what licenses it to stop calling a job unknown.
   */
  reconcile(): Promise<ReadResult>;
  /** Generation of the most recently applied read; 0 before the first one lands. */
  appliedGeneration(): number;
}

interface Gate {
  promise: Promise<ReadResult>;
  release: (result: ReadResult) => void;
}

function gate(): Gate {
  let release!: (result: ReadResult) => void;
  const promise = new Promise<ReadResult>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

export function createReadPipeline(
  load: () => Promise<Snapshot>,
  apply: (snapshot: Snapshot, generation: number) => void,
  onError: (error: unknown) => void,
): ReadPipeline {
  let issued = 0;
  let applied = 0;
  let inFlight: Promise<ReadResult> | null = null;
  /** Callers that arrived while a read was already running, all sharing one follow-up. */
  let waiting: Gate | null = null;

  /** Never rejects: a read failure is reported, not thrown at whoever happened to ask. */
  const run = (): Promise<ReadResult> => {
    const generation = ++issued;
    return load().then(
      (snapshot): ReadResult => {
        // A read that lost its race against a later one is dropped, never applied — but global
        // state is authoritative either way, which is what the caller is really asking.
        if (generation <= applied) return 'applied';
        applied = generation;
        apply(snapshot, generation);
        return 'applied';
      },
      (error: unknown): ReadResult => {
        onError(error);
        return 'failed';
      },
    ).catch((failure: unknown): ReadResult => {
      // Reporting itself failed. Swallow it here rather than leaving an unhandled rejection
      // that would also strand every waiter below.
      onError(failure);
      return 'failed';
    });
  };

  const start = (): Promise<ReadResult> => {
    const current = run().then((result) => {
      inFlight = null;
      if (!waiting) return result;
      // These callers asked *during* the read that just finished, so that read cannot satisfy
      // them. Hand them exactly one newer read, and release them with its outcome.
      const released = waiting;
      waiting = null;
      void start().then(released.release, () => released.release('failed'));
      return result;
    });
    inFlight = current;
    return current;
  };

  return {
    reconcile() {
      if (!inFlight) return start();
      // Coalesce: one gate serves everyone who arrives during this read, and callers who arrive
      // during the follow-up will open the next gate rather than being stranded on this one.
      waiting ??= gate();
      return waiting.promise;
    },
    appliedGeneration: () => applied,
  };
}
