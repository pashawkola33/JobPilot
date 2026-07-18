package com.jobpilot.resume.domain;

import com.jobpilot.candidate.domain.CandidateProject;
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
@Table(name = "resume_version_projects")
public class ResumeVersionProject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_version_id", nullable = false)
    private ResumeVersion resumeVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_project_id", nullable = false)
    private CandidateProject candidateProject;
    @Column(nullable = false)
    private int displayOrder;

    protected ResumeVersionProject() {
    }

    ResumeVersionProject(ResumeVersion resumeVersion, CandidateProject candidateProject,
                         int displayOrder) {
        this.resumeVersion = resumeVersion;
        this.candidateProject = candidateProject;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public ResumeVersion getResumeVersion() { return resumeVersion; }
    public CandidateProject getCandidateProject() { return candidateProject; }
    public int getDisplayOrder() { return displayOrder; }
}
