package com.jobpilot.candidate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "candidate_languages")
public class CandidateLanguage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_profile_id", nullable = false)
    private CandidateProfile candidateProfile;
    @Column(nullable = false, length = 100)
    private String stableKey;
    @Column(nullable = false, length = 100)
    private String language;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private CandidateLanguageLevel verifiedLevel;
    @Column(nullable = false)
    private boolean allowedInCv;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false)
    private int displayOrder;

    protected CandidateLanguage() {
    }

    CandidateLanguage(CandidateProfile candidateProfile, String stableKey, String language,
                      CandidateLanguageLevel verifiedLevel, boolean allowedInCv,
                      boolean active, int displayOrder) {
        this.candidateProfile = candidateProfile;
        this.stableKey = stableKey;
        this.language = language.trim();
        this.verifiedLevel = verifiedLevel;
        this.allowedInCv = allowedInCv;
        this.active = active;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public CandidateProfile getCandidateProfile() { return candidateProfile; }
    public String getStableKey() { return stableKey; }
    public String getLanguage() { return language; }
    public CandidateLanguageLevel getVerifiedLevel() { return verifiedLevel; }
    public boolean isAllowedInCv() { return allowedInCv; }
    public boolean isActive() { return active; }
    public int getDisplayOrder() { return displayOrder; }
}
