package com.jobpilot.resume.domain;

import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.domain.CandidateProject;
import com.jobpilot.candidate.domain.CandidateProjectBullet;
import com.jobpilot.candidate.domain.CandidateSkill;
import com.jobpilot.jobs.domain.Job;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "resume_versions")
public class ResumeVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_profile_id", nullable = false)
    private CandidateProfile candidateProfile;
    @Column(nullable = false)
    private int profileVersion;
    @Column(nullable = false, length = 200)
    private String selectedTitle;
    @Column(nullable = false, columnDefinition = "text")
    private String summary;
    @Column(nullable = false, columnDefinition = "text")
    private String plainTextPreview;
    @Column(nullable = false, columnDefinition = "text")
    private String changeSummary;
    @Column(nullable = false, columnDefinition = "text")
    private String interviewClaims;
    @Column(length = 1000)
    private String docxPath;
    @Column(length = 1000)
    private String pdfPath;
    @Column(nullable = false, length = 64)
    private String contentHash;
    @Column(nullable = false)
    private Instant generatedAt;

    @OneToMany(mappedBy = "resumeVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<ResumeVersionSkill> selectedSkills = new ArrayList<>();
    @OneToMany(mappedBy = "resumeVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<ResumeVersionProject> selectedProjects = new ArrayList<>();
    @OneToMany(mappedBy = "resumeVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<ResumeVersionProjectBullet> selectedProjectBullets = new ArrayList<>();

    protected ResumeVersion() {
    }

    public ResumeVersion(Job job, CandidateProfile candidateProfile, int profileVersion,
                         String selectedTitle, String summary, String plainTextPreview,
                         String changeSummary, String interviewClaims, String docxPath,
                         String pdfPath, String contentHash, Instant generatedAt) {
        this.job = job;
        this.candidateProfile = candidateProfile;
        this.profileVersion = profileVersion;
        this.selectedTitle = selectedTitle;
        this.summary = summary;
        this.plainTextPreview = plainTextPreview;
        this.changeSummary = changeSummary;
        this.interviewClaims = interviewClaims;
        this.docxPath = docxPath;
        this.pdfPath = pdfPath;
        this.contentHash = contentHash;
        this.generatedAt = generatedAt;
    }

    public void selectSkill(CandidateSkill skill, int displayOrder) {
        selectedSkills.add(new ResumeVersionSkill(this, skill, displayOrder));
    }

    public void selectProject(CandidateProject project, int displayOrder) {
        selectedProjects.add(new ResumeVersionProject(this, project, displayOrder));
    }

    public void selectProjectBullet(CandidateProjectBullet bullet, int displayOrder) {
        selectedProjectBullets.add(new ResumeVersionProjectBullet(this, bullet, displayOrder));
    }

    public Long getId() { return id; }
    public Job getJob() { return job; }
    public CandidateProfile getCandidateProfile() { return candidateProfile; }
    public int getProfileVersion() { return profileVersion; }
    public String getSelectedTitle() { return selectedTitle; }
    public String getSummary() { return summary; }
    public String getPlainTextPreview() { return plainTextPreview; }
    public String getChangeSummary() { return changeSummary; }
    public String getInterviewClaims() { return interviewClaims; }
    public String getDocxPath() { return docxPath; }
    public String getPdfPath() { return pdfPath; }
    public String getContentHash() { return contentHash; }
    public Instant getGeneratedAt() { return generatedAt; }
    public List<ResumeVersionSkill> getSelectedSkills() {
        return Collections.unmodifiableList(selectedSkills);
    }
    public List<ResumeVersionProject> getSelectedProjects() {
        return Collections.unmodifiableList(selectedProjects);
    }
    public List<ResumeVersionProjectBullet> getSelectedProjectBullets() {
        return Collections.unmodifiableList(selectedProjectBullets);
    }
}
