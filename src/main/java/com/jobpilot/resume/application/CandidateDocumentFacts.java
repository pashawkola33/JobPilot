package com.jobpilot.resume.application;

import com.jobpilot.candidate.domain.CandidateProfile;
import java.math.BigDecimal;
import java.util.List;

/** Detached snapshot containing only verified candidate facts and database identities. */
public record CandidateDocumentFacts(
        long profileId,
        int profileVersion,
        String sourceHash,
        String fullName,
        String location,
        String educationInstitution,
        String degree,
        int studyStartYear,
        Integer studyEndYear,
        boolean currentStudent,
        boolean finalYearStudent,
        BigDecimal commercialExperienceYears,
        List<SkillFact> skills,
        List<LanguageFact> languages,
        List<ProjectFact> projects) {

    public CandidateDocumentFacts {
        skills = List.copyOf(skills);
        languages = List.copyOf(languages);
        projects = List.copyOf(projects);
    }

    public static CandidateDocumentFacts from(CandidateProfile profile) {
        return new CandidateDocumentFacts(profile.getId(), profile.getProfileVersion(),
                profile.getSourceHash(), profile.getFullName(), profile.getLocation(),
                profile.getEducationInstitution(), profile.getDegree(),
                profile.getStudyStartYear(), profile.getStudyEndYear(),
                profile.isCurrentStudent(), profile.isFinalYearStudent(),
                profile.getCommercialJavaExperienceYears(),
                profile.getSkills().stream().map(skill -> new SkillFact(skill.getId(),
                        skill.getStableKey(), skill.getNormalizedName(), skill.getDisplayName(),
                        skill.getCategory().name(), skill.getEvidenceText(), skill.isActive(),
                        skill.getDisplayOrder())).toList(),
                profile.getLanguages().stream().map(language -> new LanguageFact(language.getId(),
                        language.getStableKey(), language.getLanguage(),
                        language.getVerifiedLevel().name(), language.isAllowedInCv(),
                        language.isActive(), language.getDisplayOrder())).toList(),
                profile.getProjects().stream().map(project -> new ProjectFact(project.getId(),
                        project.getStableKey(), project.getName(), project.getDescription(),
                        project.getProjectType().name(), project.getTechnologies(),
                        project.isActive(), project.getDisplayOrder(),
                        project.getBullets().stream().map(bullet -> new BulletFact(bullet.getId(),
                                project.getId(), project.getStableKey(), bullet.getStableKey(),
                                bullet.getVerifiedText(), bullet.getKeywords(), bullet.isActive(),
                                bullet.getDisplayOrder())).toList())).toList());
    }

    public record SkillFact(long id, String stableKey, String normalizedName,
                            String displayName, String category, String evidenceText,
                            boolean active, int displayOrder) {
    }

    public record LanguageFact(long id, String stableKey, String language,
                               String verifiedLevel, boolean allowedInCv,
                               boolean active, int displayOrder) {
    }

    public record ProjectFact(long id, String stableKey, String name, String description,
                              String projectType, List<String> technologies, boolean active,
                              int displayOrder, List<BulletFact> bullets) {
        public ProjectFact {
            technologies = List.copyOf(technologies);
            bullets = List.copyOf(bullets);
        }
    }

    public record BulletFact(long id, long projectId, String projectKey, String stableKey,
                             String verifiedText, List<String> keywords, boolean active,
                             int displayOrder) {
        public BulletFact {
            keywords = List.copyOf(keywords);
        }

        public String qualifiedKey() {
            return projectKey + ":" + stableKey;
        }
    }
}
