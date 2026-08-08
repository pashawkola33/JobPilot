package com.jobpilot.miniapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The single row that orders Mini App mutations.
 *
 * <p>Its revision counts Mini App mutations and nothing else. Telegram commands,
 * ApplicationController and ingestion all change data the snapshot shows without advancing
 * it — see docs/mini-app-p0b-consistency-model.md. Read ordering is a separate concern
 * handled by client read epochs; do not treat this as a version of the whole database.
 */
@Entity
@Table(name = "mini_app_state")
public class MiniAppState {
    /** There is exactly one row, enforced by a check constraint. */
    public static final short SINGLETON_ID = 1;

    @Id
    private short id;

    @Column(name = "mutation_revision", nullable = false)
    private long mutationRevision;

    protected MiniAppState() {
    }

    /**
     * Assigns the next revision. Callers must already hold the row's pessimistic lock, which
     * is what makes revision order equal commit order.
     */
    public long nextRevision() {
        return ++mutationRevision;
    }

    public long getMutationRevision() {
        return mutationRevision;
    }
}
