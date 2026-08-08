package com.jobpilot.miniapp.application;

import com.jobpilot.applications.domain.ApplicationStatus;
import com.jobpilot.jobreview.domain.WorkflowStatus;

/**
 * The authoritative outcome of one Mini App mutation — and deliberately <em>nothing else</em>.
 *
 * <p>This record carries no Review queue, no Saved list, no counts. That is the point. A
 * mutation's {@code mutationRevision} orders it against other Mini App mutations, but the
 * counter's domain excludes ingestion, the Telegram command path and ApplicationController, so
 * a higher revision does not make this response's view of the world newer. A mutation commits
 * with the revision it took at commit time while its transaction snapshot may predate an
 * ingestion commit a concurrent GET already returned; shipping a global snapshot here would let
 * {@code 21 > 20} erase genuinely newer out-of-band state.
 *
 * <p>So every field below describes only the job this mutation held locked through commit.
 * Global projections converge exclusively through {@code GET /snapshot}, ordered by the
 * client's read generation. See docs/mini-app-p0b-consistency-model.md.
 *
 * @param mutationId       the client's own idempotency key, echoed so a reply matches a request
 * @param mutationRevision this mutation's position among Mini App mutations only
 * @param replayed         true when the ledger resolved a duplicate instead of executing it
 * @param status           the affected job's resulting workflow status
 * @param changed          false when the job was already in the requested state
 * @param applicationStatus the affected job's resulting tracking status, or null if untracked
 * @param undoToken        a live reversal capability, or null when none exists <em>now</em>
 */
public record MiniAppOperation(
        String mutationId,
        long mutationRevision,
        boolean replayed,
        long jobId,
        WorkflowStatus status,
        boolean changed,
        ApplicationStatus applicationStatus,
        String undoToken) {
}
