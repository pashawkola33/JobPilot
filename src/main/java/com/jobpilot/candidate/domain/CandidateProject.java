package com.jobpilot.candidate.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "candidate_projects")
public class CandidateProject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_profile_id", nullable = false)
    private CandidateProfile candidateProfile;
    @Column(nullable = false, length = 100)
    private String stableKey;
    @Column(nullable = false, length = 300)
    private String name;
    @Column(nullable = false, columnDefinition = "text")
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 60)
    private ProjectType projectType;
    @Column(nullable = false, columnDefinition = "text")
    private String technologies;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false)
    private int displayOrder;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<CandidateProjectBullet> bullets = new ArrayList<>();

    protected CandidateProject() {
    }

    CandidateProject(CandidateProfile candidateProfile, String stableKey, String name,
                     String description, ProjectType projectType, List<String> technologies,
                     boolean active, int displayOrder) {
        this.candidateProfile = candidateProfile;
        this.stableKey = stableKey;
        this.name = name.trim();
        this.description = description.trim();
        this.projectType = projectType;
        this.technologies = SimpleValueCollection.encode(technologies);
        this.active = active;
        this.displayOrder = displayOrder;
    }

    public void addBullet(String stableKey, String verifiedText, List<String> keywords,
                          boolean active, int displayOrder) {
        bullets.add(new CandidateProjectBullet(this, stableKey, verifiedText, keywords,
                active, displayOrder));
    }

    public Long getId() { return id; }
    public CandidateProfile getCandidateProfile() { return candidateProfile; }
    public String getStableKey() { return stableKey; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ProjectType getProjectType() { return projectType; }
    public List<String> getTechnologies() { return SimpleValueCollection.decode(technologies); }
    public boolean isActive() { return active; }
    public int getDisplayOrder() { return displayOrder; }
    public List<CandidateProjectBullet> getBullets() { return Collections.unmodifiableList(bullets); }
}
