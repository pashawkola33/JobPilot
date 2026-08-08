package com.jobpilot.miniapp.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A reversal request. The client never says what to restore — only which mutation to undo.
 *
 * @param mutationId this reversal's own idempotency key, so a retried undo resolves rather than
 *     reversing twice
 * @param undoToken  the opaque capability the mutation being reversed issued. The server holds
 *     the prior state; the client could not reconstruct it and is never asked to
 */
public record MiniAppUndoRequest(
        @NotBlank @Size(min = 8, max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String mutationId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String undoToken) {
}
