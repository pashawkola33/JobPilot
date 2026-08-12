package com.jobpilot.candidate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The owner of one or more {@link CandidateProfile} versions.
 *
 * <p>Deliberately minimal: it establishes ownership and nothing else. Identity, credentials,
 * contact details and preferences belong to a later phase, and none of them are needed to say
 * which candidate a stored profile version describes.
 *
 * <p>{@code stableKey} is the only identity that survives a database rebuild, so configuration
 * and the seeded row are matched on it rather than on the generated id.
 *
 * <p>Ownership is modelled one-directionally from the profile. Nothing today needs to walk from a
 * candidate to its profile versions, and a mapped collection would only invite loading them all.
 */
@Entity
@Table(name = "candidates")
public class Candidate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 100)
    private String stableKey;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    protected Candidate() {
    }

    public Candidate(String stableKey, Instant now) {
        if (stableKey == null || stableKey.isBlank()) {
            throw new IllegalArgumentException("Candidate stable key is required");
        }
        this.stableKey = stableKey.trim();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public String getStableKey() { return stableKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
