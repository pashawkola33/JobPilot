package com.jobpilot.candidate.service;

import static com.jobpilot.candidate.CandidateProfileTestData.bullet;
import static com.jobpilot.candidate.CandidateProfileTestData.language;
import static com.jobpilot.candidate.CandidateProfileTestData.project;
import static com.jobpilot.candidate.CandidateProfileTestData.skill;
import static com.jobpilot.candidate.CandidateProfileTestData.validProfile;
import static com.jobpilot.candidate.CandidateProfileTestData.withExperience;
import static com.jobpilot.candidate.CandidateProfileTestData.withFullName;
import static com.jobpilot.candidate.CandidateProfileTestData.withLanguages;
import static com.jobpilot.candidate.CandidateProfileTestData.withProjects;
import static com.jobpilot.candidate.CandidateProfileTestData.withSkills;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jobpilot.candidate.domain.CandidateLanguageLevel;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CandidateProfileDefinitionValidatorTest {
    private CandidateProfileDefinitionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CandidateProfileDefinitionValidator(
                Validation.buildDefaultValidatorFactory().getValidator());
    }

    @Test
    void acceptsAValidCandidateProfile() {
        assertThatCode(() -> validator.validate(validProfile(1))).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateStableSkillKey() {
        var profile = validProfile(1);
        var invalid = withSkills(profile, List.of(skill("java", "java", 0),
                skill("java", "spring", 1)));

        assertInvalid(invalid, "duplicate stable skill key");
    }

    @Test
    void rejectsDuplicateStableProjectKey() {
        var profile = validProfile(1);
        var invalid = withProjects(profile, List.of(
                project("project", "First", List.of(bullet("one", "First fact", 0)), 0),
                project("project", "Second", List.of(bullet("two", "Second fact", 0)), 1)));

        assertInvalid(invalid, "duplicate stable project key");
    }

    @Test
    void rejectsDuplicateProjectBulletKey() {
        var profile = validProfile(1);
        var invalid = withProjects(profile, List.of(project("project", "Project", List.of(
                bullet("fact", "First fact", 0), bullet("fact", "Second fact", 1)), 0)));

        assertInvalid(invalid, "duplicate project-bullet key");
    }

    @Test
    void rejectsNegativeCommercialJavaExperience() {
        assertInvalid(withExperience(validProfile(1), new BigDecimal("-0.01")),
                "commercialJavaExperienceYears");
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertInvalid(withFullName(validProfile(1), "  "), "fullName");
    }

    @Test
    void rejectsFrenchEnabledForCvUse() {
        var invalid = withLanguages(validProfile(1), List.of(
                language("french", "French", CandidateLanguageLevel.BASIC, true, 0)));

        assertInvalid(invalid, "French must not be enabled for CV use");
    }

    @Test
    void rejectsProfessionalRomanianCvClaim() {
        var invalid = withLanguages(validProfile(1), List.of(
                language("romanian", "Romanian", CandidateLanguageLevel.FLUENT, true, 0)));

        assertInvalid(invalid, "Romanian must not be configured as professional working fluency");
    }

    private void assertInvalid(Object profile, String message) {
        assertThatThrownBy(() -> validator.validate(
                (com.jobpilot.candidate.config.CandidateProfileProperties) profile))
                .isInstanceOf(CandidateProfileValidationException.class)
                .hasMessageContaining(message);
    }
}
