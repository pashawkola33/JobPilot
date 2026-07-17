package com.jobpilot.jobs.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "job_requirements")
public class JobRequirement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne
    @JoinColumn(name = "job_id", nullable = false, unique = true)
    private Job job;
    private String seniority;
    private boolean internshipOrTrainee;
    private Double requiredExperienceYears;
    @Column(columnDefinition = "text")
    private String requiredEducation;
    private boolean finalYearMandatory;
    @Column(columnDefinition = "text")
    private String technologies;
    @Column(columnDefinition = "text")
    private String programmingLanguages;
    @Column(columnDefinition = "text")
    private String spokenLanguages;
    private String extractedLocation;
    private String remoteEligibility;
    @Column(columnDefinition = "text")
    private String mentorshipSignals;
    @Column(columnDefinition = "text")
    private String workAuthorization;
    @Column(columnDefinition = "text")
    private String salary;
    private Instant applicationDeadline;
    private String extractionMethod;
    @Column(columnDefinition = "text")
    private String rawJson;

    protected JobRequirement() {
    }

    public JobRequirement(Job job, ExtractedRequirements r, String technologies,
                          String programmingLanguages, String spokenLanguages,
                          String mentorshipSignals, String rawJson) {
        this.job = job;
        this.seniority = r.seniority();
        this.internshipOrTrainee = r.internshipOrTrainee();
        this.requiredExperienceYears = r.requiredExperienceYears();
        this.requiredEducation = r.requiredEducation();
        this.finalYearMandatory = r.finalYearMandatory();
        this.technologies = technologies;
        this.programmingLanguages = programmingLanguages;
        this.spokenLanguages = spokenLanguages;
        this.extractedLocation = r.location();
        this.remoteEligibility = r.remoteEligibility();
        this.mentorshipSignals = mentorshipSignals;
        this.workAuthorization = r.workAuthorization();
        this.salary = r.salary();
        this.applicationDeadline = r.applicationDeadline();
        this.extractionMethod = r.extractionMethod();
        this.rawJson = rawJson;
    }

    public ExtractedRequirements toValue() {
        return new ExtractedRequirements(seniority, internshipOrTrainee, requiredExperienceYears,
                requiredEducation, finalYearMandatory, split(technologies),
                split(programmingLanguages), split(spokenLanguages), extractedLocation,
                remoteEligibility, split(mentorshipSignals), workAuthorization, salary,
                applicationDeadline, extractionMethod);
    }

    private static java.util.List<String> split(String value) {
        return value == null || value.isBlank() ? java.util.List.of()
                : java.util.Arrays.stream(value.split("\\|", -1)).filter(s -> !s.isBlank()).toList();
    }

    public Job getJob() { return job; }
}
