package com.jobpilot.resume.domain;

import java.util.List;

/** Canonical, provider-neutral and renderer-neutral resume content. */
public record ResumeDocumentModel(
        String fullName,
        DocumentContactBlock contact,
        String selectedRoleTitle,
        String professionalSummary,
        Education education,
        List<Skill> skills,
        List<Language> languages,
        List<Project> projects,
        List<String> changeSummary,
        List<InterviewClaim> interviewClaims,
        String templateVersion) {

    public ResumeDocumentModel {
        skills = List.copyOf(skills);
        languages = List.copyOf(languages);
        projects = List.copyOf(projects);
        changeSummary = List.copyOf(changeSummary);
        interviewClaims = List.copyOf(interviewClaims);
    }

    public record Education(String institution, String degree, int startYear,
                            Integer endYear, boolean currentStudent) {
    }

    public record Skill(long factId, String stableKey, String normalizedName,
                        String displayName) {
    }

    public record Language(long factId, String stableKey, String language,
                           String verifiedLevel) {
    }

    public record Project(long factId, String stableKey, String name, String description,
                          String projectType, List<String> technologies, List<Bullet> bullets) {
        public Project {
            technologies = List.copyOf(technologies);
            bullets = List.copyOf(bullets);
        }
    }

    public record Bullet(long factId, String stableKey, String verifiedText) {
    }

    public record InterviewClaim(String statement, List<String> factKeys) {
        public InterviewClaim {
            factKeys = List.copyOf(factKeys);
        }
    }
}
