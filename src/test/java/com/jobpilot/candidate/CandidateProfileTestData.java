package com.jobpilot.candidate;

import com.jobpilot.candidate.config.CandidateProfileProperties;
import com.jobpilot.candidate.domain.CandidateLanguageLevel;
import com.jobpilot.candidate.domain.CandidateSkillCategory;
import com.jobpilot.candidate.domain.ProjectType;
import java.math.BigDecimal;
import java.util.List;

public final class CandidateProfileTestData {
    private CandidateProfileTestData() {
    }

    public static CandidateProfileProperties validProfile(int version) {
        return new CandidateProfileProperties(
                version,
                "Test Candidate",
                "Bucharest, Romania",
                "Test University",
                "BSc in Informatics",
                2025,
                null,
                true,
                false,
                BigDecimal.ZERO,
                List.of(skill("java", "java", 0)),
                List.of(language("english", "English", CandidateLanguageLevel.FLUENT, true, 0)),
                List.of(project("sample-project", "Sample Project",
                        List.of(bullet("verified-api", "Implements a verified REST API.", 0)), 0)));
    }

    public static CandidateProfileProperties withSkills(CandidateProfileProperties source,
                                                        List<CandidateProfileProperties.Skill> skills) {
        return copy(source, source.profileVersion(), source.fullName(),
                source.commercialJavaExperienceYears(), skills, source.languages(), source.projects());
    }

    public static CandidateProfileProperties withLanguages(
            CandidateProfileProperties source, List<CandidateProfileProperties.Language> languages) {
        return copy(source, source.profileVersion(), source.fullName(),
                source.commercialJavaExperienceYears(), source.skills(), languages, source.projects());
    }

    public static CandidateProfileProperties withProjects(
            CandidateProfileProperties source, List<CandidateProfileProperties.Project> projects) {
        return copy(source, source.profileVersion(), source.fullName(),
                source.commercialJavaExperienceYears(), source.skills(), source.languages(), projects);
    }

    public static CandidateProfileProperties withExperience(CandidateProfileProperties source,
                                                            BigDecimal experience) {
        return copy(source, source.profileVersion(), source.fullName(), experience,
                source.skills(), source.languages(), source.projects());
    }

    public static CandidateProfileProperties withFullName(CandidateProfileProperties source,
                                                          String fullName) {
        return copy(source, source.profileVersion(), fullName,
                source.commercialJavaExperienceYears(), source.skills(), source.languages(),
                source.projects());
    }

    public static CandidateProfileProperties withVersion(CandidateProfileProperties source,
                                                         int version) {
        return copy(source, version, source.fullName(), source.commercialJavaExperienceYears(),
                source.skills(), source.languages(), source.projects());
    }

    public static CandidateProfileProperties.Skill skill(String stableKey, String normalizedName,
                                                          int displayOrder) {
        return new CandidateProfileProperties.Skill(stableKey, normalizedName, normalizedName,
                CandidateSkillCategory.JAVA, "Verified test evidence", true, displayOrder);
    }

    public static CandidateProfileProperties.Language language(
            String stableKey, String language, CandidateLanguageLevel level,
            boolean allowedInCv, int displayOrder) {
        return new CandidateProfileProperties.Language(
                stableKey, language, level, allowedInCv, true, displayOrder);
    }

    public static CandidateProfileProperties.Project project(
            String stableKey, String name, List<CandidateProfileProperties.Bullet> bullets,
            int displayOrder) {
        return new CandidateProfileProperties.Project(stableKey, name,
                "Verified personal project", ProjectType.PERSONAL_PROJECT,
                List.of("Java", "Spring Boot"), true, displayOrder, bullets);
    }

    public static CandidateProfileProperties.Bullet bullet(String stableKey, String text,
                                                           int displayOrder) {
        return new CandidateProfileProperties.Bullet(
                stableKey, text, List.of("Java"), true, displayOrder);
    }

    private static CandidateProfileProperties copy(
            CandidateProfileProperties source, int version, String fullName,
            BigDecimal experience, List<CandidateProfileProperties.Skill> skills,
            List<CandidateProfileProperties.Language> languages,
            List<CandidateProfileProperties.Project> projects) {
        return new CandidateProfileProperties(version, fullName, source.location(),
                source.educationInstitution(), source.degree(), source.studyStartYear(),
                source.studyEndYear(), source.currentStudent(), source.finalYearStudent(),
                experience, skills, languages, projects);
    }
}
