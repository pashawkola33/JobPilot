package com.jobpilot.resume.domain;

import com.jobpilot.candidate.domain.CandidateProjectBullet;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "resume_version_project_bullets")
public class ResumeVersionProjectBullet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_version_id", nullable = false)
    private ResumeVersion resumeVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_project_bullet_id", nullable = false)
    private CandidateProjectBullet candidateProjectBullet;
    @Column(nullable = false)
    private int displayOrder;

    protected ResumeVersionProjectBullet() {
    }

    ResumeVersionProjectBullet(ResumeVersion resumeVersion,
                               CandidateProjectBullet candidateProjectBullet, int displayOrder) {
        this.resumeVersion = resumeVersion;
        this.candidateProjectBullet = candidateProjectBullet;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public ResumeVersion getResumeVersion() { return resumeVersion; }
    public CandidateProjectBullet getCandidateProjectBullet() { return candidateProjectBullet; }
    public int getDisplayOrder() { return displayOrder; }
}
