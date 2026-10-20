package com.jobpilot.miniapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per candidate orders that candidate's Mini App mutations.
 *
 * <p>Its revision counts Mini App workflow mutations and v2 application edits. Telegram commands,
 * ApplicationController and ingestion all change data the snapshot shows without advancing
 * it — see docs/mini-app-p0b-consistency-model.md. Read ordering is a separate concern
 * handled by client read epochs; do not treat this as a version of the whole database.
 */
@Entity
@Table(name = "mini_app_state")
public class MiniAppState {
    /** Legacy SQL discriminator retained as 1 for every candidate; candidate_id is the identity. */
    public static final short SINGLETON_ID = 1;

    private short id;

    @Id
    @Column(name = "candidate_id")
    private Long candidateId;

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
