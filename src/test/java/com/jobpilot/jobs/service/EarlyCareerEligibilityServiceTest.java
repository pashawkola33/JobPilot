package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.EarlyCareerEligibility;
import com.jobpilot.jobs.domain.RawCareerData;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.jobs.domain.SeniorityLevel;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EarlyCareerEligibilityServiceTest {
    private final EarlyCareerEligibilityService service = new EarlyCareerEligibilityService();

    @ParameterizedTest
    @MethodSource("earlyCareerTitles")
    void acceptsExplicitEarlyCareerTitles(String title, SeniorityLevel level) {
        var decision = service.evaluate(raw(title, "Build software with the product team."));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.ELIGIBLE);
        assertThat(decision.seniorityLevel()).isEqualTo(level);
        assertThat(decision.eligibilityReason()).isNotBlank();
        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(decision.reasons()).isNotEmpty();
    }

    static Stream<Arguments> earlyCareerTitles() {
        return Stream.of(
                Arguments.of("Software Engineer Intern", SeniorityLevel.INTERNSHIP),
                Arguments.of("Java Trainee", SeniorityLevel.TRAINEE),
                Arguments.of("Software Engineering Apprentice", SeniorityLevel.TRAINEE),
                Arguments.of("Working Student — Backend", SeniorityLevel.WORKING_STUDENT),
                Arguments.of("Graduate Software Engineer", SeniorityLevel.GRADUATE),
                Arguments.of("Entry-Level QA Engineer", SeniorityLevel.ENTRY_LEVEL),
                Arguments.of("Junior Java Developer", SeniorityLevel.JUNIOR));
    }

    @ParameterizedTest
    @MethodSource("rejectedTitles")
    void rejectsNonEarlyCareerTitles(String title, SeniorityLevel level) {
        var decision = service.evaluate(raw(title, "Build software with the product team."));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.INELIGIBLE);
        assertThat(decision.seniorityLevel()).isEqualTo(level);
        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REJECT);
    }

    static Stream<Arguments> rejectedTitles() {
        return Stream.of(
                Arguments.of("Mid-Level Java Developer", SeniorityLevel.MID_LEVEL),
                Arguments.of("Senior Software Engineer", SeniorityLevel.SENIOR),
                Arguments.of("Lead Java Developer", SeniorityLevel.LEADERSHIP),
                Arguments.of("Engineering Leadership Roles", SeniorityLevel.LEADERSHIP),
                Arguments.of("Staff Engineer", SeniorityLevel.LEADERSHIP),
                Arguments.of("Principal Engineer", SeniorityLevel.LEADERSHIP),
                Arguments.of("Solution Architect", SeniorityLevel.LEADERSHIP),
                Arguments.of("Engineering Manager", SeniorityLevel.LEADERSHIP),
                Arguments.of("Head of Engineering", SeniorityLevel.LEADERSHIP),
                Arguments.of("Associate Director", SeniorityLevel.LEADERSHIP),
                Arguments.of("VP Engineering", SeniorityLevel.LEADERSHIP),
                Arguments.of("Chief Technology Executive", SeniorityLevel.LEADERSHIP));
    }

    @Test
    void descriptionRequirementOverridesJuniorTitle() {
        var decision = service.evaluate(raw("Junior Developer",
                "Candidates must have at least 4 years of professional experience."));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.INELIGIBLE);
        assertThat(decision.experienceRequirement().minimumYears()).isEqualTo(4.0);
        assertThat(decision.experienceRequirement().mandatory()).isTrue();
        assertThat(decision.eligibilityReason()).contains("4 years");
    }

    @ParameterizedTest
    @MethodSource("descriptionEligibility")
    void acceptsClearEarlyCareerDescriptionWithoutJuniorInTitle(String description,
                                                                 SeniorityLevel level) {
        var decision = service.evaluate(raw("Software Engineer", description));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.ELIGIBLE);
        assertThat(decision.seniorityLevel()).isEqualTo(level);
    }

    static Stream<Arguments> descriptionEligibility() {
        return Stream.of(
                Arguments.of("No previous professional experience required.", SeniorityLevel.ENTRY_LEVEL),
                Arguments.of("New graduates are welcome to apply.", SeniorityLevel.GRADUATE),
                Arguments.of("Suitable for students or recent graduates.", SeniorityLevel.GRADUATE),
                Arguments.of("Requires 0–2 years of relevant experience.", SeniorityLevel.ENTRY_LEVEL),
                Arguments.of("Requires up to 2 years of professional experience.", SeniorityLevel.ENTRY_LEVEL),
                Arguments.of("This is an internship position on the backend team.",
                        SeniorityLevel.INTERNSHIP));
    }

    @Test
    void acceptsPreferredButNotRequiredExperienceForJuniorRole() {
        var decision = service.evaluate(raw("Junior Java Developer",
                "Three years are preferred but not required. 3+ years of experience is a plus."));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.ELIGIBLE);
        assertThat(decision.experienceRequirement().mandatory()).isFalse();
    }

    @Test
    void projectsCourseworkAndInternshipsDoNotBecomeCommercialExperience() {
        var decision = service.evaluate(raw("Software Engineer Intern",
                "3 years of university project experience is helpful. Coursework, personal projects, "
                        + "internships, a GitHub portfolio, and basic familiarity with Java are accepted."));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.ELIGIBLE);
        assertThat(decision.experienceRequirement().known()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("leadershipRequirements")
    void rejectsLeadershipResponsibilitiesEvenWhenTitleLooksJunior(String requirement) {
        var decision = service.evaluate(raw("Junior Software Engineer", requirement));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.INELIGIBLE);
        assertThat(decision.seniorityLevel()).isEqualTo(SeniorityLevel.LEADERSHIP);
    }

    static Stream<String> leadershipRequirements() {
        return Stream.of(
                "You will manage an engineering team and have direct reports.",
                "You will take ownership of the department.",
                "The role requires technical leadership across the team.",
                "You will own the platform architecture.",
                "Your primary responsibility is to mentor junior engineers.",
                "You will manage senior stakeholders across the company.");
    }

    @Test
    void keepsGenericRoleUnknown() {
        var decision = service.evaluate(raw("Software Engineer",
                "Build Java services and collaborate with the team."));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.UNKNOWN);
        assertThat(decision.seniorityLevel()).isEqualTo(SeniorityLevel.UNKNOWN);
        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REVIEW);
    }

    @Test
    void doesNotTreatLeadGenerationAsEngineeringLeadership() {
        var decision = service.evaluate(raw("Lead Generation Specialist",
                "Support business development campaigns."));

        assertThat(decision.seniorityLevel()).isEqualTo(SeniorityLevel.UNKNOWN);
        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REVIEW);
    }

    @Test
    void usesStructuredProviderFactsBeforeTextInference() {
        RawJob raw = raw("Software Engineer", "Build Java services.",
                new RawCareerData("entry_level", 0.0, 2.0, true, "0-2 years"));

        var decision = service.evaluate(raw);

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.ELIGIBLE);
        assertThat(decision.seniorityLevel()).isEqualTo(SeniorityLevel.ENTRY_LEVEL);
        assertThat(decision.experienceRequirement().maximumYears()).isEqualTo(2.0);
    }

    @Test
    void reviewsStructuredMandatoryThreeYears() {
        RawJob raw = raw("Junior Developer", "Build Java services.",
                new RawCareerData("junior", 3.0, null, true, "3+ years"));

        assertThat(service.evaluate(raw).earlyCareerEligibility())
                .isEqualTo(EarlyCareerEligibility.UNKNOWN);
    }

    @Test
    void threeMandatoryYearsAreNotWeakenedByOptionalTextInAnotherSentence() {
        var decision = service.evaluate(raw("Junior Developer",
                "Nice-to-have: exposure to cloud platforms. Candidates must have 3+ years "
                        + "of professional experience."));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.UNKNOWN);
        assertThat(decision.experienceRequirement().mandatory()).isTrue();
    }

    @Test
    void noExperiencePhraseDoesNotHideAConflictingMandatoryRequirement() {
        var decision = service.evaluate(raw("Junior Developer",
                "No previous experience required for the onboarding course. The role requires "
                        + "4 years of commercial experience."));

        assertThat(decision.earlyCareerEligibility()).isEqualTo(EarlyCareerEligibility.INELIGIBLE);
    }

    private RawJob raw(String title, String description) {
        return raw(title, description, RawCareerData.empty());
    }

    private RawJob raw(String title, String description, RawCareerData careerData) {
        return new RawJob("fixture", "1", "https://example.com/jobs/1", title, "Example",
                "Bucharest, Romania", description, null, null, null, "{}",
                RawLocationData.empty(), "example", careerData);
    }
}
