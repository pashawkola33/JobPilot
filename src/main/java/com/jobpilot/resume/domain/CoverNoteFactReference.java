package com.jobpilot.resume.domain;

import com.jobpilot.candidate.domain.CandidateLanguage;
import com.jobpilot.candidate.domain.CandidateProfile;
import com.jobpilot.candidate.domain.CandidateProject;
import com.jobpilot.candidate.domain.CandidateProjectBullet;
import com.jobpilot.candidate.domain.CandidateSkill;
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
@Table(name = "cover_note_fact_references")
public class CoverNoteFactReference {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cover_note_id", nullable = false)
    private CoverNote coverNote;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CoverNoteFactType factType;
    @Column(nullable = false, length = 100)
    private String factKey;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_profile_id")
    private CandidateProfile candidateProfile;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_skill_id")
    private CandidateSkill candidateSkill;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_language_id")
    private CandidateLanguage candidateLanguage;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_project_id")
    private CandidateProject candidateProject;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "candidate_project_bullet_id")
    private CandidateProjectBullet candidateProjectBullet;
    @Column(nullable = false)
    private int displayOrder;

    protected CoverNoteFactReference() {
    }

    private CoverNoteFactReference(CoverNote note, CoverNoteFactType type, String key, int order) {
        coverNote = note;
        factType = type;
        factKey = key;
        displayOrder = order;
    }

    static CoverNoteFactReference profile(CoverNote note, CandidateProfile value, int order) {
        CoverNoteFactReference reference = new CoverNoteFactReference(
                note, CoverNoteFactType.PROFILE, "profile:" + value.getProfileVersion(), order);
        reference.candidateProfile = value;
        return reference;
    }

    static CoverNoteFactReference skill(CoverNote note, CandidateSkill value, int order) {
        CoverNoteFactReference reference = new CoverNoteFactReference(
                note, CoverNoteFactType.SKILL, value.getStableKey(), order);
        reference.candidateSkill = value;
        return reference;
    }

    static CoverNoteFactReference language(CoverNote note, CandidateLanguage value, int order) {
        CoverNoteFactReference reference = new CoverNoteFactReference(
                note, CoverNoteFactType.LANGUAGE, value.getStableKey(), order);
        reference.candidateLanguage = value;
        return reference;
    }

    static CoverNoteFactReference project(CoverNote note, CandidateProject value, int order) {
        CoverNoteFactReference reference = new CoverNoteFactReference(
                note, CoverNoteFactType.PROJECT, value.getStableKey(), order);
        reference.candidateProject = value;
        return reference;
    }

    static CoverNoteFactReference bullet(CoverNote note, CandidateProjectBullet value, int order) {
        CoverNoteFactReference reference = new CoverNoteFactReference(
                note, CoverNoteFactType.PROJECT_BULLET,
                value.getProject().getStableKey() + ":" + value.getStableKey(), order);
        reference.candidateProjectBullet = value;
        return reference;
    }

    public Long getId() { return id; }
    public CoverNote getCoverNote() { return coverNote; }
    public CoverNoteFactType getFactType() { return factType; }
    public String getFactKey() { return factKey; }
    public CandidateProfile getCandidateProfile() { return candidateProfile; }
    public CandidateSkill getCandidateSkill() { return candidateSkill; }
    public CandidateLanguage getCandidateLanguage() { return candidateLanguage; }
    public CandidateProject getCandidateProject() { return candidateProject; }
    public CandidateProjectBullet getCandidateProjectBullet() { return candidateProjectBullet; }
    public int getDisplayOrder() { return displayOrder; }
}
