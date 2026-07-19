package com.jobpilot.resume.domain;

import com.jobpilot.candidate.domain.CandidateLanguage;
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
@Table(name = "resume_version_languages")
public class ResumeVersionLanguage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_version_id", nullable = false)
    private ResumeVersion resumeVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_language_id", nullable = false)
    private CandidateLanguage candidateLanguage;
    @Column(nullable = false)
    private int displayOrder;

    protected ResumeVersionLanguage() {
    }

    ResumeVersionLanguage(ResumeVersion resumeVersion, CandidateLanguage candidateLanguage,
                          int displayOrder) {
        this.resumeVersion = resumeVersion;
        this.candidateLanguage = candidateLanguage;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public ResumeVersion getResumeVersion() { return resumeVersion; }
    public CandidateLanguage getCandidateLanguage() { return candidateLanguage; }
    public int getDisplayOrder() { return displayOrder; }
}
