package com.jobpilot.candidate.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "candidate_profiles")
public class CandidateProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private int profileVersion;
    @Column(nullable = false, length = 200)
    private String fullName;
    @Column(nullable = false, length = 300)
    private String location;
    @Column(nullable = false, length = 300)
    private String educationInstitution;
    @Column(nullable = false, length = 300)
    private String degree;
    @Column(nullable = false)
    private int studyStartYear;
    private Integer studyEndYear;
    @Column(nullable = false)
    private boolean currentStudent;
    @Column(nullable = false)
    private boolean finalYearStudent;
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal commercialJavaExperienceYears;
    @Column(nullable = false, length = 64)
    private String sourceHash;
    @Column(nullable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;
    @Column(nullable = false)
    private boolean active;
    private Short activeSlot;

    @OneToMany(mappedBy = "candidateProfile", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<CandidateSkill> skills = new ArrayList<>();
    @OneToMany(mappedBy = "candidateProfile", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<CandidateLanguage> languages = new ArrayList<>();
    @OneToMany(mappedBy = "candidateProfile", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC, id ASC")
    private final List<CandidateProject> projects = new ArrayList<>();

    protected CandidateProfile() {
    }

    public CandidateProfile(int profileVersion, String fullName, String location,
                            String educationInstitution, String degree, int studyStartYear,
                            Integer studyEndYear, boolean currentStudent, boolean finalYearStudent,
                            BigDecimal commercialJavaExperienceYears, String sourceHash,
                            Instant now, boolean active) {
        this.profileVersion = profileVersion;
        this.fullName = fullName;
        this.location = location;
        this.educationInstitution = educationInstitution;
        this.degree = degree;
        this.studyStartYear = studyStartYear;
        this.studyEndYear = studyEndYear;
        this.currentStudent = currentStudent;
        this.finalYearStudent = finalYearStudent;
        this.commercialJavaExperienceYears = commercialJavaExperienceYears;
        this.sourceHash = sourceHash;
        this.createdAt = now;
        this.updatedAt = now;
        this.active = active;
        this.activeSlot = active ? (short) 1 : null;
    }

    public void addSkill(String stableKey, String normalizedName, String displayName,
                         CandidateSkillCategory category, String evidenceText,
                         boolean active, int displayOrder) {
        skills.add(new CandidateSkill(this, stableKey, normalizedName, displayName,
                category, evidenceText, active, displayOrder));
    }

    public void addLanguage(String stableKey, String language, CandidateLanguageLevel verifiedLevel,
                            boolean allowedInCv, boolean active, int displayOrder) {
        languages.add(new CandidateLanguage(this, stableKey, language, verifiedLevel,
                allowedInCv, active, displayOrder));
    }

    public CandidateProject addProject(String stableKey, String name, String description,
                                       ProjectType projectType, List<String> technologies,
                                       boolean active, int displayOrder) {
        CandidateProject project = new CandidateProject(this, stableKey, name, description,
                projectType, technologies, active, displayOrder);
        projects.add(project);
        return project;
    }

    public void deactivate(Instant now) {
        active = false;
        activeSlot = null;
        updatedAt = now;
    }

    public Long getId() { return id; }
    public int getProfileVersion() { return profileVersion; }
    public String getFullName() { return fullName; }
    public String getLocation() { return location; }
    public String getEducationInstitution() { return educationInstitution; }
    public String getDegree() { return degree; }
    public int getStudyStartYear() { return studyStartYear; }
    public Integer getStudyEndYear() { return studyEndYear; }
    public boolean isCurrentStudent() { return currentStudent; }
    public boolean isFinalYearStudent() { return finalYearStudent; }
    public BigDecimal getCommercialJavaExperienceYears() { return commercialJavaExperienceYears; }
    public String getSourceHash() { return sourceHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public boolean isActive() { return active; }
    public List<CandidateSkill> getSkills() { return Collections.unmodifiableList(skills); }
    public List<CandidateLanguage> getLanguages() { return Collections.unmodifiableList(languages); }
    public List<CandidateProject> getProjects() { return Collections.unmodifiableList(projects); }
}
