# Phase 2 implementation plan

## Approved scope and design

Implement the user's Phase 2 request in the existing worktree without committing, pushing, deploying, accessing production, or changing V1–V16. Phase 1 preflight found no Critical/High issue. Its Medium internal-route perimeter caveat remains documented; private route denial must not be loosened by Phase 2.

Keep `jobs` shared. Add explicit candidate ownership to workflows, applications, history, revisions and mutation records. Resolve legacy scope deliberately to the `default` candidate, never by first login or arbitrary runtime identity. New HTTP services must revalidate the Phase 1 immutable principal before delegating to candidate-scoped storage. SQL and resource lookups carry candidate identity. Never expose legacy scores or reasons in a beta snapshot.

Use V17 or later, preserving original IDs, versions, timestamps, notes, history and delivery rows. Enforce candidate/job uniqueness, candidate/mutation-key and candidate/revision uniqueness, and same-owner history/document associations in PostgreSQL. Preserve READ COMMITTED serialized mutation/replay transactions and REPEATABLE READ snapshots. The lock/revision row belongs to one candidate.

Keep v1 owner-only and its JSON contract stable. Reuse its workflow/snapshot/undo mechanics for v2 where possible. V2 provides snapshot, workflow mutation, undo, application detail/history/notes/follow-up/status. A v2 body or URL can identify a resource but never supplies its owner. Unknown or foreign resources return the same not-found/stale response.

The frontend retains owner v1 behavior; beta uses bearer-authenticated v2 and a clearly labeled neutral catalogue, independent saved/application state and undo. Credentials remain in memory and every operation/retry remains bound to its originating generation. Reopening with fresh Telegram launch data restores persisted workflow state; spent-launch reload still requires reopening.

Previous Phase 1 binaries contain globally scoped queries and cannot be assumed safe after multi-candidate records exist. Rehearse this boundary explicitly. If no verified bridge exists, prohibit rollback to Phase 1 after beta writes, require a compatible candidate-aware binary or forward fix, and mark later release readiness NO-GO. Do not write destructive downgrade scripts.

## Execution

The user explicitly requested implementation and instructed proceeding after a passing preflight. Execute this design directly, using independent backend/frontend work streams and one integration/rehearsal coordinator; no additional authorization or git commits.

- [x] Independently review actual Phase 1 diff, constraints and rollback evidence.
- [x] Backend: write failing ownership/concurrency tests; implement V17 and candidate-scoped storage/services; preserve owner callers and protect document associations.
- [x] API: extend authenticated v2 with scoped workflows and application tracking, using explicit validated principal and bounded catalogue projections.
- [x] Frontend: write failing multi-account/generation tests; extend the gate and transport; reuse existing consistency helpers for neutral beta workflow.
- [x] Integration: real PostgreSQL HTTP tests for two synthetic accounts, independent state/history/notes, cross-owner resource IDs and undo, idempotency conflicts and concurrent revisions, anonymous/expired denial and v1 owner restriction.
- [x] Migration: synthetic deployed V13→latest preservation, ownership/constraints, repeat migration/startup checks, previous binary compatibility rehearsal.
- [x] Run full Java, PostgreSQL, migration, auth, concurrency, frontend type/lint/API build and Playwright suites; reject missing or skipped required PostgreSQL tests.
- [x] Independently review completed change, fix security findings, record exact results/limitations and a synthetic two-account demonstration. Stop at Phase 2.

## Review focus

1. A foreign application's numeric ID or undo token must never reveal or modify its owner.
2. Candidate notes, follow-up edits and out-of-band transitions must make older undo stale.
3. Same mutation key in different accounts is independent; conflicting payload within one account is rejected.
4. Inactive/legacy global scores and reasons must not escape via beta catalogue/application projections.
5. A pending request from an expired/replaced account must not retry or reconcile with the next account's credentials.
