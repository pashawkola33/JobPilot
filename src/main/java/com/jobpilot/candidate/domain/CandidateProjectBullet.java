package com.jobpilot.candidate.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "candidate_project_bullets")
public class CandidateProjectBullet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private CandidateProject project;
    @Column(nullable = false, length = 100)
    private String stableKey;
    @Column(nullable = false, columnDefinition = "text")
    private String verifiedText;
    @Column(nullable = false, columnDefinition = "text")
    private String keywords;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false)
    private int displayOrder;

    protected CandidateProjectBullet() {
    }

    CandidateProjectBullet(CandidateProject project, String stableKey, String verifiedText,
                           List<String> keywords, boolean active, int displayOrder) {
        this.project = project;
        this.stableKey = stableKey;
        this.verifiedText = verifiedText.trim();
        this.keywords = SimpleValueCollection.encode(keywords);
        this.active = active;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public CandidateProject getProject() { return project; }
    public String getStableKey() { return stableKey; }
    public String getVerifiedText() { return verifiedText; }
    public List<String> getKeywords() { return SimpleValueCollection.decode(keywords); }
    public boolean isActive() { return active; }
    public int getDisplayOrder() { return displayOrder; }
}
