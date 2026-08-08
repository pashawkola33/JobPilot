package com.jobpilot.miniapp.repository;

import com.jobpilot.miniapp.domain.MiniAppMutation;
import com.jobpilot.miniapp.domain.MiniAppReversalState;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MiniAppMutationRepository extends JpaRepository<MiniAppMutation, Long> {

    /**
     * The idempotency lookup, issued inside the {@code mini_app_state} lock so a duplicate that
     * queued behind the original sees its committed row. The unique constraint remains the real
     * guarantee; this is what turns a duplicate into an answer instead of an error.
     */
    Optional<MiniAppMutation> findByMutationKey(String mutationKey);

    /**
     * Undo addresses a mutation by opaque token. Spent tokens still resolve — liveness is
     * {@code reversalState}, not the token's presence — so a superseded or already-reversed undo
     * is refused as stale rather than as unknown, and a replayed undo can prove which mutation
     * it consumed.
     */
    Optional<MiniAppMutation> findByUndoToken(String undoToken);

    /** Every still-reversible mutation for a job, so a newer one can supersede them. */
    List<MiniAppMutation> findByJobIdAndReversalState(long jobId, MiniAppReversalState state);

    /** Newest first: the staleness check on every reversal. */
    Optional<MiniAppMutation> findTopByJobIdOrderByMutationRevisionDesc(long jobId);
}
