package com.jobpilot.resume.domain;

import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.jobs.domain.Job;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "cover_notes")
public class CoverNote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_profile_id", nullable = false)
    private CandidateProfile candidateProfile;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_version_id")
    private ResumeVersion resumeVersion;
    @Column(nullable = false, columnDefinition = "text")
    private String content;
    @Column(nullable = false, length = 64)
    private String contentHash;
    @Column(nullable = false)
    private Instant generatedAt;

    protected CoverNote() {
    }

    public CoverNote(Job job, CandidateProfile candidateProfile, ResumeVersion resumeVersion,
                     String content, String contentHash, Instant generatedAt) {
        this.job = job;
        this.candidateProfile = candidateProfile;
        this.resumeVersion = resumeVersion;
        this.content = content;
        this.contentHash = contentHash;
        this.generatedAt = generatedAt;
    }

    public Long getId() { return id; }
    public Job getJob() { return job; }
    public CandidateProfile getCandidateProfile() { return candidateProfile; }
    public ResumeVersion getResumeVersion() { return resumeVersion; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public Instant getGeneratedAt() { return generatedAt; }
}
