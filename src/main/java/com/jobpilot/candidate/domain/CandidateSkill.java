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
@Table(name = "candidate_skills")
public class CandidateSkill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_profile_id", nullable = false)
    private CandidateProfile candidateProfile;
    @Column(nullable = false, length = 100)
    private String stableKey;
    @Column(nullable = false, length = 200)
    private String normalizedName;
    @Column(nullable = false, length = 200)
    private String displayName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private CandidateSkillCategory category;
    @Column(nullable = false, columnDefinition = "text")
    private String evidenceText;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false)
    private int displayOrder;

    protected CandidateSkill() {
    }

    CandidateSkill(CandidateProfile candidateProfile, String stableKey, String normalizedName,
                   String displayName, CandidateSkillCategory category, String evidenceText,
                   boolean active, int displayOrder) {
        this.candidateProfile = candidateProfile;
        this.stableKey = stableKey;
        this.normalizedName = normalizedName.trim();
        this.displayName = displayName.trim();
        this.category = category;
        this.evidenceText = evidenceText.trim();
        this.active = active;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public CandidateProfile getCandidateProfile() { return candidateProfile; }
    public String getStableKey() { return stableKey; }
    public String getNormalizedName() { return normalizedName; }
    public String getDisplayName() { return displayName; }
    public CandidateSkillCategory getCategory() { return category; }
    public String getEvidenceText() { return evidenceText; }
    public boolean isActive() { return active; }
    public int getDisplayOrder() { return displayOrder; }
}
