# Mini App P0-B consistency model

Base: `fea94fe6bf1a0debcd43dbf31bbab27c922b1882` (P0-A durability, iOS sheet fix,
document-wait fix all merged).

P0-A made a single Mini App mutation atomic and durable. It did not make a *sequence* of
mutations deterministic. This document fixes the invariants P0-B enforces, and records the
two decisions that shape the schema, so the implementation and its tests can be checked
against something written down rather than against intent.

Two corrections were applied to this model before implementation, both listed here because
each deletes a rule that looks correct and is not:

- **A mutation revision cannot version a global snapshot.** A higher `mutationRevision` does
  not make a response's view of Review/Saved/Applications newer, because the counter's domain
  excludes the writers that change them. Mutation responses therefore carry no global state.
- **A read cannot resolve an ambiguous timeout.** A GET is authoritative about its own
  database moment and proves nothing about whether an in-flight mutation will commit.
  Recovery is addressed to the mutation id, through the ledger.

## What is actually broken

Mutation responses carry a **global** snapshot. Per-request ordering is therefore not
enough: a response for job 10 can replace state produced by a later mutation on job 11.

| # | Hole | Consequence |
|---|---|---|
| A | No per-job mutation state machine | `Save(10)` then `Apply(10)` can settle as SAVED |
| B | No snapshot ordering | An older global snapshot overwrites a newer one |
| C | Timeout is ambiguous | Server may have committed while the client believes it failed |
| D | Undo is reconstructed on the client | Cannot tell pre-existing tracking from tracking the Mini App created |
| E | Applied reversal disabled in P0-A | Temporary limitation, must become explicit |
| F | Mutation *and* recovery read both fail | UI silently keeps a state nothing confirmed |
| G | Late recovery read | Can overwrite a newer successful action |
| H | Mutation revision mistaken for a global version | A committing mutation erases a newer out-of-band change |
| I | Recovery read treated as a commit oracle | Client rules a mutation failed that then commits |

## Invariants

The implementation must make each of these true, and each has at least one deterministic
test naming it.

- **I1 — same-job order.** Operations on one job apply in user-intent order. A response
  that is not for the newest operation on that job is discarded, never applied.
- **I2 — job independence.** A job's in-flight mutation never blocks a different job on
  the client. There is no global pending token.
- **I3 — no regression.** The client never applies a response that would move it backwards.
  Global projections are replaced *only* by the authoritative read pipeline, ordered by read
  generation — never by a mutation response, whatever revision it carries. An out-of-band
  change at equal revision must still be applied; a delayed earlier read must not.
- **I4 — revision order is commit order.** A revision is only visible once its transaction
  committed, and revisions become visible in increasing order. Proven by a test that makes
  the transaction which *starts* first commit second.
- **I5 — atomic unit.** Workflow row, application row, application history, ledger entry
  and revision increment commit together or not at all.
- **I6 — replay safety.** Re-sending a mutation with the same key applies the logical
  operation once. Duplicate history rows are impossible.
- **I7 — reversal validity.** An Undo applies only if its mutation is still the newest for
  that job, has not already been reversed, **and** the durable state still matches the
  fingerprint that mutation produced — so an external Telegram/API change invalidates it
  too. Otherwise: typed 409 plus an authoritative snapshot to reconcile from.
- **I8 — no destructive inference.** Nothing is deleted because of its current status. The
  ledger must prove this mutation created it and that nothing has touched it since.
- **I9 — honest unknown.** An ambiguous mutation is resolved by re-sending *its own
  mutation id*, never by a bare read. When that resolution also fails, the job is marked
  *reconciliation required*: the client shows neither the optimistic state nor a rollback as
  confirmed, refuses further mutations for that job, and offers a deterministic retry.

## Revision model (decision 1, corrected)

### The domain is Mini App mutations, not the snapshot

`mini_app_state.mutation_revision` **does not version everything the snapshot shows.**
Calling it a snapshot revision would be a lie, and a dangerous one. The writer inventory:

| Writer | Touches | Advances the revision? |
|---|---|---|
| Mini App `PUT`/reversal | `job_workflow_state`, `applications`, history | yes |
| `TelegramCommandDispatcher` SAVE/APPLIED/DISMISS/RESET/NOTE | `job_workflow_state` | **no** |
| `TelegramCommandDispatcher` application commands, `ApplicationController` | `applications`, history | **no** |
| Ingestion / scoring / expiry | `jobs`, `job_scores` → Review projection and counts | **no** |

So two different states can both be labelled revision 10 — exactly the S1/S2 hazard.
Rejecting `<=` would then be wrong, and accepting `==` would let a delayed read overwrite
a newer one.

Extending the revision to every writer (option A) would drag ingestion, the Telegram
command path and the scheduler into this change. That is scope creep this work explicitly
forbids. **Option B is taken:** the counter is named `miniAppMutationRevision`, means only
what it says, and read ordering is handled separately.

### A mutation revision cannot version a global snapshot

The tempting rule — *"this response carries a higher `mutationRevision`, so its whole
snapshot is newer"* — is **false**, and P0-B must not contain it anywhere.

Under option B the counter orders Mini App mutations against each other. It says nothing
about the rest of the read model, which three other writers change without touching it. A
mutation's revision is assigned at its commit, but the data it *read* comes from whenever
its transaction snapshot was established. Those are different moments:

```
mutationRevision = 20
Mini App mutation M begins, DB snapshot established
ingestion commits a new Review vacancy          <- M cannot see this
GET starts after ingestion, returns revision 20 + the new vacancy
M commits, returns revision 21                  <- higher, but blind to the vacancy
```

`21 > 20`, yet M's view of the Review queue is strictly *older* than the GET's. Applying
M's global snapshot because its revision is higher erases a genuinely newer out-of-band
change. The same hole exists for every Telegram and `ApplicationController` write.

The fix is not a cleverer comparison — no counter whose domain excludes a writer can order
that writer's effects. It is to stop mutations from carrying global state at all.

### Two response kinds with different authority

**A mutation response is an operation result, not a view of the world.** `PUT` and undo
return only what the operation authoritatively decided:

| Field | Meaning |
|---|---|
| `mutationId` | the client's own key, so a reply is matched to its request |
| `mutationRevision` | where this mutation sits among Mini App mutations |
| `jobId`, `status`, `changed`, `updatedAt` | the affected job's resulting workflow state |
| `application` | the affected job's resulting tracking state, or null |
| `undo` | a live undo capability, or null if none exists *now* |
| `replayed` | true when the ledger resolved it rather than executing it |

Every one of those is about **the affected job**, which the mutation held locked to commit.
None of it is a claim about Review totals, Saved lists, or application counts.

**Global projections converge only through `GET /snapshot`.** That path is transactionally
consistent (below) and ordered by a client **read generation**: the client assigns each read
an increasing generation, and applies a response only if its generation is the newest issued.
Revision is not used to order reads at all, so an out-of-band change at equal revision is
applied normally, while a delayed earlier read is dropped because a later generation exists.

Reads are **single-flight**. Any number of callers may request reconciliation; while one is
in flight the rest coalesce onto it, and a request arriving mid-flight schedules exactly one
follow-up read rather than queueing per caller. An older read can therefore never overwrite a
newer reconciliation, and reconciliation cannot starve under repeated requests. Mutations on
unrelated jobs are never blocked by this — the single-flight rule governs reads only (I2).

The P0-A `snapshot` field is **removed** from the mutation response rather than kept for
compatibility. Keeping it would preserve the exact footgun this section exists to delete, and
the Mini App frontend is the only client. Building it also forced ~12 extra queries into the
mutation's transaction, lengthening the window the global row lock is held for no remaining
correctness purpose.

### Lock timing (I4)

Inside one `REQUIRES_NEW` transaction, in this exact order:

1. `SELECT … FOR UPDATE` on `mini_app_state` — **first statement**, before any other work
2. resolve the ledger by `mutation_key`; a hit means this is a duplicate, so return its
   recorded result and do no further work (see idempotency, below)
3. increment `mutation_revision`
4. workflow mutation (`JobReviewService`)
5. application mutation (`ApplicationTrackerService.transitionInCurrentTransaction`)
6. supersede any prior `REVERSIBLE` ledger row for this job
7. insert this mutation's ledger row (previous-state + resulting-state fingerprint)
8. commit

The ledger lookup is deliberately *inside* the lock. Checking before it would let a duplicate
race past the original instead of queueing behind it, which is the whole mechanism of §Timeout
and recovery. No global snapshot is built here at all — that work moved to the read path.

No network or external I/O is ever performed while the lock is held. The increment is in
the same transaction as the state change, so a rolled-back transaction publishes no
revision and leaves no ledger row. Because the lock is taken first and held to commit, the
sequence "snapshot for N built → unrelated mutation N+1 commits → N commits later" cannot
occur: N+1 cannot even begin its revision assignment until N commits.

Accepted cost: Mini App mutations serialize server-side on that row. Correct-first, and at
single-user scope only ever contended by one person's taps. It does not violate I2 — the
client still issues job B without waiting for job A. Multi-user would move this to per-user
revision ownership; deliberately not implemented here.

### Isolation: the two paths need opposite things

`MiniAppSnapshotService.snapshot()` issues ~12 statements. At the default READ_COMMITTED each
statement sees a *fresh* database moment, so a concurrent commit lands half-visible — stats
from one moment, rows from another. **The read path therefore runs at REPEATABLE READ**, so a
snapshot is exactly one database moment. It takes no locks and never touches `mini_app_state`.

The mutation path must **not** copy that, and this was verified against PostgreSQL 16 rather
than assumed. At REPEATABLE READ, a `SELECT … FOR UPDATE` that blocks on a row another
transaction has since committed does not queue — it aborts:

```
session B (repeatable read), blocked on the row A holds, after A commits:
  ERROR:  could not serialize access due to concurrent update
```

The same interleaving at READ COMMITTED blocks, then re-reads A's committed value and
proceeds. So REPEATABLE READ would turn every contended Mini App mutation into a serialization
abort, and — worse — would break duplicate handling: a duplicate that aborts on the lock never
reaches the ledger lookup that is supposed to recognise it, and an aborted transaction's
snapshot could not see the original's committed ledger row anyway.

**The mutation path therefore runs at READ COMMITTED with explicit row locks.** It reads no
global projection, so it has nothing to keep coherent across statements; every row it decides
on (`mini_app_state`, the workflow row, the application row) it holds a lock on. This is only
available because mutations no longer build a snapshot — Correction A pays for itself here.

Row locking gives the mutation path three properties at once: revision order equals commit
order, duplicates queue behind the original instead of failing, and a blocked mutation sees
committed state when it wakes.

## Idempotency model

Forward mutations are already idempotent *by value*: `ApplicationTrackerService` short-
circuits when `previous == requested` and writes no history, and `JobReviewService` only
flushes when `update(...)` reports a change. The existing
`repeatedSavedAndAppliedCommandsAreIdempotent` proves it. So the ledger is not needed to
protect forward writes.

It is needed for two things value-idempotency cannot give:

1. **Stable responses.** A retry after a lost response must return the same `revision` and
   the same undo descriptor as the original, not a fresh one.
2. **Single-application of reversal.** Undo is not value-idempotent. Replaying it must not
   reverse twice.

Each mutation carries a client-generated `mutationId`. The ledger stores it `UNIQUE`, so
duplicate delivery is resolved by the database, not by application logic.

**The ledger stores operation identity, never a snapshot.** Replaying `mutationId=X` after a
later mutation B must not resurrect X's old view of the world. On replay the server:

- does **not** re-execute — no second workflow write, no second history row, no second
  reversal;
- returns X's recorded identity (`mutationRevision`, `changed`) so the client can recognise
  its own operation, marked `replayed`;
- carries **no global state**, so a replay of X cannot regress the client past B no matter how
  late it arrives. This is Correction A doing the work: there is no stale snapshot to replay
  because the response never had one;
- presents an undo capability only if X's ledger row is *still* `REVERSIBLE`. B's commit
  will have marked X `SUPERSEDED` and cleared its token, so a replayed A never re-arms a
  stale Undo.

A repeat with the same key but a different payload is a typed 409 — that is a client bug,
not a retry.

## Undo semantics (decision 2)

Undo is a server operation against a recorded mutation, addressed by an opaque server-issued
token. The client never reconstructs prior state.

The ledger records, transactionally with the mutation: previous workflow status/note/
applied-at (null = UNREVIEWED, i.e. no row), previous application status (null = no
application existed), whether this mutation created the application row, and which history
row it appended.

There is **one** reversal rule, not one per case. Applied in order, inside the mutation
transaction, after the freshness check passes:

1. restore the workflow row to `previous_workflow_status` (null ⇒ delete the row, back to
   implicit UNREVIEWED), with its recorded note and applied-at;
2. delete `created_history_id` if this mutation appended one — that row and no other;
3. if `created_application`, delete the application row. History first:
   `application_status_history` is `ON DELETE RESTRICT`, so the child must go before the parent;
4. otherwise restore `previous_application_status` and `previous_application_applied_at`
   verbatim, bypassing `ApplicationTransitionPolicy`.

The four cases are consequences of that one rule, not separate code paths:

| Case | Before | Result of the rule |
|---|---|---|
| **1 — pre-existing SAVED** | `application(SAVED)`, then Apply | step 2 removes the history row the Apply appended; step 4 restores SAVED and the null applied-at. Row identity and every pre-existing history row survive |
| **2 — Mini App created tracking** | no application, then Save/Apply | steps 2 and 3 delete the history row then the application row — only because the ledger *proves* this mutation created them, never because of their current status (I8) |
| **3 — pre-existing APPLIED or later** | `application(APPLIED)` etc. | unreachable from a reversible mutation: Apply onto an already-APPLIED application is `changed=false`, which is recorded `NOT_REVERSIBLE` and issues no token. Should it ever arise, step 4 restores the recorded status directly rather than manufacturing a backwards transition |
| **4 — Dismiss** | any | `created_history_id` is null and `previous_application_status` already equals the current status, so steps 2–4 are no-ops. Tracking is untouched |

Step 4 is why the ledger records `previous_application_applied_at`. Reversing SAVED → APPLIED
has to put back the exact applied-at that was there — which was null — and a rule that
*inferred* "restoring to SAVED means clear the date" would be the same class of guess that I8
exists to forbid.

Case 2 is the one place anything is deleted. The guard is the point of the ledger: deletion
is allowed because we recorded that we created the row, not inferred from its status (I8).

### Provenance is not freshness

`created_application` and `created_history_id` say what this mutation *made*. They do not
say it is still the state being reversed. Consider:

```
Mini App Save   -> application(SAVED) + history H1     (created_application = true)
Telegram /applied -> application(APPLIED) + history H2  (no revision change)
old Mini App Undo arrives
```

Provenance alone would happily delete H1 and the application row, erasing a later external
action. So the ledger also records a **fingerprint of the state the mutation produced**:
`resulting_workflow_status`, `resulting_application_version` (the JPA `@Version`) and
`resulting_history_id` (the history frontier). A reversal is valid only while all three
still match, checked under row locks on the workflow and application rows.

Any later writer moves at least one: a Telegram or API transition bumps `@Version` and
appends history; a notes/follow-up change bumps `@Version`; a newer Mini App mutation moves
the ledger frontier. So an old Undo is stale deterministically, with no timestamps involved,
and it refuses with a typed conflict plus an authoritative snapshot rather than deleting
anything.

`ApplicationTransitionPolicy` is **not** given an `APPLIED → SAVED` edge. Reversal is a
distinct operation with a distinct source, so a normal forward transition still cannot walk
backwards.

## Timeout and recovery

A network failure is not a transaction failure. A timeout tells the client that *it* stopped
waiting; it says nothing about whether the server transaction stopped.

### A read is not a commit-status oracle

So this recovery is invalid, and P0-B must not contain it:

```
PUT mutationId=X starts
client times out
recovery GET runs, sees the old state
client concludes X failed          <- unjustified
X commits a moment later
```

The GET was honest about its own database moment. It was never evidence about X's *future*.
Any recovery that reasons from a read alone can only be right by luck.

### Recovery is addressed to the mutation, not to the world

The ambiguous request is resolved by re-sending **the same `mutationId` with the same
payload**. The ledger, not the application logic, decides what that means, and PostgreSQL
provides the serialization:

- **original already committed** — the retry takes the `mini_app_state` lock, finds X's
  ledger row, and returns X's recorded result. No second workflow write, no second history
  row, no second reversal.
- **original still running** — the retry blocks on the `mini_app_state` row lock the original
  holds until commit. It cannot execute concurrently and it cannot pass the original. When
  the original commits, the retry wakes at READ COMMITTED, sees the committed ledger row, and
  resolves as a duplicate. This is why the ledger lookup sits inside the lock.
- **original rolled back** — no ledger row exists, so the retry executes, exactly once.
- **same key, different payload** — a typed 409. That is a client bug, not a retry.

No `IN_PROGRESS` column is needed: the row lock supplies the waiting and the `UNIQUE`
constraint supplies the exactly-once. Both are proven against real PostgreSQL rather than
assumed.

Only once `mutationId` has a **terminal known outcome** does the client queue an authoritative
snapshot reconciliation, and only then is the affected job authoritative again. A read is
useful *after* mutation identity resolves — never instead of it.

If the same-id resolution also fails, the job enters *reconciliation required* (I9): no
confirmed state, no further mutations for that job, an explicit retry affordance, and the
rest of the app stays usable.

## Frontend state machine

Two independent pieces of state, matching the two response kinds.

**Per job:** `{ status, sequence, inFlightMutationId, undo, error }`. Same-job operations
serialize through a queue on that entry, so `Save(10)` then `Apply(10)` cannot settle as
SAVED (I1); different jobs have independent entries and never block each other (I2). An
operation result is applied to its job only if its `sequence` is still the newest for that
job — a superseded reply is dropped, not applied.

**Global:** the projections (Review, Saved, Applications, counts) plus `readGeneration`.
These are written *only* by the authoritative read pipeline, never by a mutation reply.
A read is applied only if it is the newest generation issued (I3), and reads are single-flight
with one coalesced follow-up, so a slow read can never overwrite a newer one.

The two connect in one direction only: a settled mutation *requests* reconciliation. It never
writes global state itself. That is what makes an out-of-band Telegram or ingestion change
survive a concurrently committing Mini App mutation with a higher revision.

`reconciliationRequired` is a per-job terminal state (I9), reachable only when both the
mutation-id resolution and the reconciliation that follows it have failed. It blocks further
mutations for that job alone and offers a deterministic retry.

## Test matrix

Every row is deterministic — latches, barriers and deferred promises, never a sleep as the
proof. The concurrency-sensitive subset runs 20 consecutive times.

| Area | Case | Invariant |
|---|---|---|
| Ordering | Save → Apply same job settles APPLIED | I1 |
| Ordering | same-job responses arrive reordered | I1 |
| Ordering | different jobs mutate concurrently | I2 |
| Ordering | revision order equals commit order (first-started commits second) | I4 |
| Ordering | revision increments only on committed mutations; rollback publishes none | I4, I5 |
| Reads | out-of-band change at equal revision is applied | I3 |
| Reads | out-of-band change survives a later, higher-revision mutation | I3, H |
| Reads | delayed earlier read never overwrites a later one | I3, G |
| Reads | snapshot is one database moment under concurrent commit | I3 |
| Recovery | timeout after commit → same-id retry, no re-execution | I6, C |
| Recovery | original still running → duplicate queues, one effect only | I6, I |
| Recovery | original rolls back → same-id retry executes exactly once | I6 |
| Recovery | same id, different payload → typed 409 | I6 |
| Recovery | mutation and resolution both fail → reconciliation required | I9 |
| Undo | pre-existing SAVED → Apply → Undo keeps row and history | I7, I8 |
| Undo | Mini-App-created tracking is removed, history first | I8 |
| Undo | Dismiss undo touches no application | I7 |
| Undo | Applied reversal is explicit, no APPLIED→SAVED policy edge | E |
| Undo | stale after a newer Mini App action | I7 |
| Undo | stale after an external Telegram/API transition | I7 |
| Undo | replay of a successful undo does not reverse twice | I6 |
| Existing | MiniAppConflictIT, 49/50/51 Review boundaries, Telegram iOS sheet suite | — |

## Out of scope

Multi-user isolation, scheduler/poller ownership, Review pagination beyond Load next batch,
dependency upgrades, deployment. Production is untouched until this is reviewed and green.
