package com.jobpilot.resume.domain;

import com.jobpilot.candidate.domain.CandidateSkill;
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
@Table(name = "resume_version_skills")
public class ResumeVersionSkill {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_version_id", nullable = false)
    private ResumeVersion resumeVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_skill_id", nullable = false)
    private CandidateSkill candidateSkill;
    @Column(nullable = false)
    private int displayOrder;

    protected ResumeVersionSkill() {
    }

    ResumeVersionSkill(ResumeVersion resumeVersion, CandidateSkill candidateSkill, int displayOrder) {
        this.resumeVersion = resumeVersion;
        this.candidateSkill = candidateSkill;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public ResumeVersion getResumeVersion() { return resumeVersion; }
    public CandidateSkill getCandidateSkill() { return candidateSkill; }
    public int getDisplayOrder() { return displayOrder; }
}
