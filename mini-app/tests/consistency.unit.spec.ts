import { expect, test } from '@playwright/test';
import { createJobQueue, isAmbiguous, sendWithRecovery } from '../src/lib/jobMutations';
import { createReadPipeline } from '../src/lib/readPipeline';
import { JobPilotError, type Snapshot } from '../src/data/types';

/**
 * The ordering guarantees, exercised where they live.
 *
 * These are not browser tests on purpose. The review UI advances the card the moment a decision
 * is made, so it cannot itself issue two concurrent mutations for one job — driving these
 * through the DOM would test the card animation, not the queue. Every race below is forced with
 * a deferred promise resolved by hand, so nothing depends on timing.
 */

/** A promise this test resolves when it chooses to. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const snapshotWithTotal = (total: number) =>
  ({
    reviewQueue: { items: [], total, limit: 50, truncated: false },
    saved: { items: [], total: 0, limit: 50, truncated: false },
    applications: { items: [], total: 0, limit: 20, truncated: false },
    applicationJobs: [],
    workflowCounts: {
      unreviewedMatch: total, unreviewedReview: 0, saved: 0, applied: 0, dismissed: 0,
    },
    applicationCounts: {
      total: 0, saved: 0, applied: 0, interview: 0, offer: 0, rejected: 0, withdrawn: 0,
    },
  }) as Snapshot;

// --------------------------------------------------------------------- per-job queue

test('operations on one job run in issue order even when the first is slower', async () => {
  const queue = createJobQueue();
  const first = deferred<string>();
  const settled: string[] = [];

  const a = queue.run(7, () => first.promise.then((value) => { settled.push(value); }));
  const b = queue.run(7, () => Promise.resolve().then(() => { settled.push('second'); }));

  // The second must not slip past the first, however fast its own work is.
  await Promise.resolve();
  await Promise.resolve();
  expect(settled).toEqual([]);

  first.resolve('first');
  await Promise.all([a, b]);
  expect(settled).toEqual(['first', 'second']);
});

test('a job in flight never blocks a different job', async () => {
  const queue = createJobQueue();
  const blocked = deferred<void>();
  const settled: string[] = [];

  const slow = queue.run(7, () => blocked.promise.then(() => { settled.push('slow-job-7'); }));
  await queue.run(8, () => Promise.resolve().then(() => { settled.push('fast-job-8'); }));

  // Job 8 finished while job 7 is still open: there is no global pending token.
  expect(settled).toEqual(['fast-job-8']);
  blocked.resolve();
  await slow;
  expect(settled).toEqual(['fast-job-8', 'slow-job-7']);
});

test('a rejected operation does not block the job forever', async () => {
  const queue = createJobQueue();

  await expect(queue.run(7, () => Promise.reject(new Error('boom')))).rejects.toThrow('boom');
  // A poisoned chain would leave this pending for the life of the session.
  await expect(queue.run(7, () => Promise.resolve('recovered'))).resolves.toBe('recovered');
});

test('settled jobs do not accumulate queue entries', async () => {
  const queue = createJobQueue();

  for (let jobId = 0; jobId < 50; jobId += 1) {
    await queue.run(jobId, () => Promise.resolve());
  }
  await Promise.resolve();

  // A map that only ever grows would leak one promise per reviewed vacancy.
  expect(queue.size()).toBe(0);
});

// ------------------------------------------------------------------ ambiguity policy

test('a transport failure is ambiguous and a typed refusal is not', () => {
  expect(isAmbiguous(new JobPilotError('unavailable'))).toBe(true);
  expect(isAmbiguous(new TypeError('fetch failed'))).toBe(true);
  // These are definite answers from a server that was reached; retrying repeats a decision.
  expect(isAmbiguous(new JobPilotError('conflict'))).toBe(false);
  expect(isAmbiguous(new JobPilotError('not-found'))).toBe(false);
  expect(isAmbiguous(new JobPilotError('undo-stale'))).toBe(false);
  expect(isAmbiguous(new JobPilotError('forbidden'))).toBe(false);
});

test('an ambiguous failure is resolved by re-sending, and a refusal is not', async () => {
  let calls = 0;
  const recovered = await sendWithRecovery(() => {
    calls += 1;
    return calls === 1
      ? Promise.reject(new JobPilotError('unavailable'))
      : Promise.resolve({ mutationId: 'm', replayed: true } as never);
  });
  expect(calls).toBe(2);
  expect(recovered).toEqual({ mutationId: 'm', replayed: true });

  let refusals = 0;
  await expect(sendWithRecovery(() => {
    refusals += 1;
    return Promise.reject(new JobPilotError('conflict'));
  })).rejects.toThrow();
  expect(refusals).toBe(1);
});

test('a double failure propagates so the caller can mark the job unknown', async () => {
  let calls = 0;
  await expect(sendWithRecovery(() => {
    calls += 1;
    return Promise.reject(new JobPilotError('unavailable'));
  })).rejects.toThrow();
  // Exactly one resolution attempt — not a retry loop multiplying requests.
  expect(calls).toBe(2);
});

// -------------------------------------------------------------------- read pipeline

/**
 * The contract that makes an awaited reconciliation mean something. A caller arriving during a
 * read cannot be satisfied by that read — its body was already fetched before they asked — so
 * its promise must stay pending until a genuinely later read lands.
 */
test('a caller arriving during a read waits for the follow-up, not the in-flight read', async () => {
  const gates = [deferred<Snapshot>(), deferred<Snapshot>()];
  let reads = 0;
  const pipeline = createReadPipeline(
    () => gates[reads++]!.promise,
    () => {},
    () => {},
  );

  const first = pipeline.reconcile();
  let firstSettled = false;
  void first.then(() => { firstSettled = true; });

  const second = pipeline.reconcile();
  let secondSettled = false;
  void second.then(() => { secondSettled = true; });

  gates[0]!.resolve(snapshotWithTotal(1));
  await first;
  await new Promise((resolve) => setImmediate(resolve));

  expect(firstSettled).toBe(true);
  // The read it asked for has not happened yet, so it must not be resolved.
  expect(secondSettled).toBe(false);
  expect(reads).toBe(2);

  gates[1]!.resolve(snapshotWithTotal(2));
  await expect(second).resolves.toBe('applied');
  expect(secondSettled).toBe(true);
  expect(pipeline.appliedGeneration()).toBe(2);
});

test('a read superseded by a newer applied one still reports authoritative state', async () => {
  const slow = deferred<Snapshot>();
  const fast = deferred<Snapshot>();
  const bodies = [slow.promise, fast.promise];
  let issued = 0;
  const pipeline = createReadPipeline(() => bodies[issued++]!, () => {}, () => {});

  const first = pipeline.reconcile();
  const second = pipeline.reconcile();
  fast.resolve(snapshotWithTotal(99));
  slow.resolve(snapshotWithTotal(1));

  // The caller asked whether global state is authoritative now, not whether its own body won.
  await expect(first).resolves.toBe('applied');
  await expect(second).resolves.toBe('applied');
});

test('every caller arriving during a read is released by the same follow-up', async () => {
  const gates = [deferred<Snapshot>(), deferred<Snapshot>(), deferred<Snapshot>()];
  let reads = 0;
  const pipeline = createReadPipeline(
    () => gates[reads++]!.promise,
    () => {},
    () => {},
  );

  const first = pipeline.reconcile();
  const waiters = [pipeline.reconcile(), pipeline.reconcile(), pipeline.reconcile()];
  const settled = waiters.map(() => false);
  waiters.forEach((waiter, index) => void waiter.then(() => { settled[index] = true; }));

  gates[0]!.resolve(snapshotWithTotal(1));
  await first;
  await new Promise((resolve) => setImmediate(resolve));
  expect(settled).toEqual([false, false, false]);
  // Three callers, one follow-up — not one read each.
  expect(reads).toBe(2);

  gates[1]!.resolve(snapshotWithTotal(2));
  await Promise.all(waiters);
  expect(settled).toEqual([true, true, true]);
  expect(reads).toBe(2);
});

test('a caller arriving during the follow-up gets a later read rather than being stranded', async () => {
  const gates = [deferred<Snapshot>(), deferred<Snapshot>(), deferred<Snapshot>()];
  let reads = 0;
  const pipeline = createReadPipeline(
    () => gates[reads++]!.promise,
    () => {},
    () => {},
  );

  const first = pipeline.reconcile();
  const duringFirst = pipeline.reconcile();
  gates[0]!.resolve(snapshotWithTotal(1));
  await first;
  await new Promise((resolve) => setImmediate(resolve));

  // The follow-up is now the in-flight read; this caller needs one after it.
  const duringFollowUp = pipeline.reconcile();
  let strandedCheck = false;
  void duringFollowUp.then(() => { strandedCheck = true; });

  gates[1]!.resolve(snapshotWithTotal(2));
  await duringFirst;
  await new Promise((resolve) => setImmediate(resolve));
  expect(strandedCheck).toBe(false);

  gates[2]!.resolve(snapshotWithTotal(3));
  await duringFollowUp;
  expect(strandedCheck).toBe(true);
  expect(reads).toBe(3);
});

test('a failed follow-up settles its waiters and leaves the pipeline usable', async () => {
  const gates = [deferred<Snapshot>(), deferred<Snapshot>(), deferred<Snapshot>()];
  let reads = 0;
  const errors: unknown[] = [];
  const pipeline = createReadPipeline(
    () => gates[reads++]!.promise,
    () => {},
    (error) => errors.push(error),
  );

  const first = pipeline.reconcile();
  const waiter = pipeline.reconcile();
  gates[0]!.resolve(snapshotWithTotal(1));
  await first;
  await new Promise((resolve) => setImmediate(resolve));

  gates[1]!.reject(new JobPilotError('unavailable'));
  // Deterministically settled rather than left hanging, and settled with the truth: the caller
  // must be able to tell "authoritative state applied" from "it did not", because the recovery
  // state machine only releases a blocked job on the former.
  await expect(waiter).resolves.toBe('failed');
  expect(errors).toHaveLength(1);

  // And a later reconciliation still works, reporting that it applied.
  const third = pipeline.reconcile();
  gates[2]!.resolve(snapshotWithTotal(9));
  await expect(third).resolves.toBe('applied');
  expect(pipeline.appliedGeneration()).toBe(3);
});

test('concurrent reconcile requests coalesce onto one read plus one follow-up', async () => {
  const gates = [deferred<Snapshot>(), deferred<Snapshot>()];
  let reads = 0;
  const applied: number[] = [];
  const pipeline = createReadPipeline(
    () => gates[reads++]!.promise,
    (snapshot) => applied.push(snapshot.reviewQueue.total),
    () => {},
  );

  const first = pipeline.reconcile();
  // Three more callers while the first read is in flight: one follow-up covers them all.
  pipeline.reconcile();
  pipeline.reconcile();
  pipeline.reconcile();
  expect(reads).toBe(1);

  gates[0]!.resolve(snapshotWithTotal(1));
  await first;
  expect(reads).toBe(2);

  gates[1]!.resolve(snapshotWithTotal(2));
  await gates[1]!.promise;
  await new Promise((resolve) => setImmediate(resolve));

  expect(applied).toEqual([1, 2]);
  expect(reads).toBe(2);
});

test('a read that resolves late never overwrites a newer one', async () => {
  const slow = deferred<Snapshot>();
  const fast = deferred<Snapshot>();
  const bodies = [slow.promise, fast.promise];
  let issued = 0;
  const applied: number[] = [];
  const pipeline = createReadPipeline(
    () => bodies[issued++]!,
    (snapshot) => applied.push(snapshot.reviewQueue.total),
    () => {},
  );

  const first = pipeline.reconcile();
  pipeline.reconcile();
  // The newer read lands first, then the older body finally arrives carrying stale data.
  fast.resolve(snapshotWithTotal(99));
  slow.resolve(snapshotWithTotal(1));
  await first;
  await new Promise((resolve) => setImmediate(resolve));

  // Generation 1 applied, then generation 2. The stale body is never applied on top.
  expect(applied).toEqual([1, 99]);
  expect(applied.at(-1)).toBe(99);
});

test('a failed read is reported and does not wedge the pipeline', async () => {
  let reads = 0;
  const errors: unknown[] = [];
  const pipeline = createReadPipeline(
    () => {
      reads += 1;
      return reads === 1
        ? Promise.reject(new JobPilotError('unavailable'))
        : Promise.resolve(snapshotWithTotal(5));
    },
    () => {},
    (error) => errors.push(error),
  );

  await pipeline.reconcile();
  expect(errors).toHaveLength(1);
  // The next request still runs: one bad read must not disable reconciliation for the session.
  await pipeline.reconcile();
  expect(pipeline.appliedGeneration()).toBe(2);
});
