package com.jobpilot.matching.preview;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.ExtractedRequirements;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.RequirementField;
import com.jobpilot.matching.preview.ScoreRescorePreviewReport.RequirementFieldChange;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The requirement diff is what makes a planned row explainable before a guarded write, so it is
 * exercised directly rather than only through the preview service.
 */
class RequirementDiffTest {
    private final ScoreRescorePreviewService service =
            new ScoreRescorePreviewService(null, null, null, null);

    private List<RequirementFieldChange> diff(ExtractedRequirements stored,
                                              ExtractedRequirements computed) {
        return ReflectionTestSupport.requirementDiff(service, stored, computed);
    }

    @Test
    void reportsTechnologiesOnly() {
        var stored = base();
        var computed = withTechnologies(base(), List.of("Java", "REST"));

        assertThat(diff(stored, computed)).singleElement().satisfies(change -> {
            assertThat(change.field()).isEqualTo(RequirementField.TECHNOLOGIES);
            assertThat(change.storedValue()).isEqualTo("[Java]");
            assertThat(change.computedValue()).isEqualTo("[Java, REST]");
        });
    }

    @Test
    void reportsProgrammingLanguagesOnly() {
        var computed = new ExtractedRequirements("JUNIOR", false, null, null, false,
                List.of("Java"), List.of("Java", "Go"), List.of(), "Bucharest",
                "Romania eligible", List.of(), null, null, null, "DETERMINISTIC");

        assertThat(diff(base(), computed)).singleElement()
                .satisfies(change -> assertThat(change.field())
                        .isEqualTo(RequirementField.PROGRAMMING_LANGUAGES));
    }

    /** The documented pre-existing drift: stored JUNIOR, freshly UNKNOWN. */
    @Test
    void reportsSeniorityOnly() {
        var computed = withSeniority(base(), "UNKNOWN");

        assertThat(diff(base(), computed)).singleElement().satisfies(change -> {
            assertThat(change.field()).isEqualTo(RequirementField.SENIORITY);
            assertThat(change.storedValue()).isEqualTo("JUNIOR");
            assertThat(change.computedValue()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    void reportsEveryDifferingFieldInRecordOrder() {
        var computed = new ExtractedRequirements("UNKNOWN", true, 2.0, "BSc", true,
                List.of("Java", "Go"), List.of("Java", "Go"), List.of("English (B2)"), "Cluj",
                "Remote from Romania allowed", List.of("mentor"), "visa", "1000 EUR",
                Instant.parse("2026-09-01T00:00:00Z"), "DETERMINISTIC");

        assertThat(diff(base(), computed)).extracting(RequirementFieldChange::field)
                .containsExactly(RequirementField.SENIORITY,
                        RequirementField.INTERNSHIP_OR_TRAINEE,
                        RequirementField.REQUIRED_EXPERIENCE_YEARS,
                        RequirementField.REQUIRED_EDUCATION,
                        RequirementField.FINAL_YEAR_MANDATORY,
                        RequirementField.TECHNOLOGIES,
                        RequirementField.PROGRAMMING_LANGUAGES,
                        RequirementField.SPOKEN_LANGUAGES,
                        RequirementField.LOCATION,
                        RequirementField.REMOTE_ELIGIBILITY,
                        RequirementField.MENTORSHIP_SIGNALS,
                        RequirementField.WORK_AUTHORIZATION,
                        RequirementField.SALARY,
                        RequirementField.APPLICATION_DEADLINE);
    }

    @Test
    void reportsNothingWhenRequirementsAreIdentical() {
        assertThat(diff(base(), base())).isEmpty();
    }

    /** Record equality is order-sensitive, so a reorder is a real change and must be shown. */
    @Test
    void reportsListReorderAsAChange() {
        var computed = withTechnologies(base(), List.of("REST", "Java"));
        var stored = withTechnologies(base(), List.of("Java", "REST"));

        assertThat(diff(stored, computed)).singleElement()
                .satisfies(change -> assertThat(change.field())
                        .isEqualTo(RequirementField.TECHNOLOGIES));
    }

    /** Comparison happens before truncation, so a late difference cannot be hidden. */
    @Test
    void reportsValuesDifferingOnlyBeyondTheDisplayLimit() {
        String prefix = "x".repeat(RequirementFieldChange.MAX_VALUE_LENGTH + 20);
        var stored = withEducation(base(), prefix + "AAA");
        var computed = withEducation(base(), prefix + "BBB");

        assertThat(diff(stored, computed)).singleElement().satisfies(change -> {
            assertThat(change.field()).isEqualTo(RequirementField.REQUIRED_EDUCATION);
            assertThat(change.storedValue()).hasSizeLessThanOrEqualTo(
                    RequirementFieldChange.MAX_VALUE_LENGTH);
            assertThat(change.storedValue()).endsWith("…");
        });
    }

    @Test
    void distinguishesNullFromEmpty() {
        var stored = withEducation(base(), null);
        var computed = withEducation(base(), "");

        assertThat(diff(stored, computed)).singleElement().satisfies(change -> {
            assertThat(change.storedValue()).isEqualTo("(null)");
            assertThat(change.computedValue()).isEqualTo("(empty)");
        });
    }

    @Test
    void rendersWhitespaceOnlyValuesAsEmpty() {
        assertThat(diff(base(), withEducation(base(), "   "))).singleElement()
                .satisfies(change -> {
                    assertThat(change.storedValue()).isEqualTo("(null)");
                    assertThat(change.computedValue()).isEqualTo("(empty)");
                });
        assertThat(diff(base(), withEducation(base(), "\n\t  "))).singleElement()
                .satisfies(change -> assertThat(change.computedValue()).isEqualTo("(empty)"));
    }

    @Test
    void collapsesAndStripsWhitespaceAroundRealText() {
        assertThat(diff(base(), withEducation(base(), "  BSc   Computer\n Science  ")))
                .singleElement()
                .satisfies(change -> assertThat(change.computedValue())
                        .isEqualTo("BSc Computer Science"));
    }

    /** Raw equality decides the change; rendering only explains it afterwards. */
    @Test
    void detectsAChangeBetweenTwoValuesThatBothRenderAsEmpty() {
        var stored = withEducation(base(), "");
        var computed = withEducation(base(), "   ");

        assertThat(diff(stored, computed)).singleElement().satisfies(change -> {
            assertThat(change.field()).isEqualTo(RequirementField.REQUIRED_EDUCATION);
            assertThat(change.storedValue()).isEqualTo("(empty)");
            assertThat(change.computedValue()).isEqualTo("(empty)");
        });
    }

    @Test
    void boundsLongListsWithAVisibleRemainder() {
        List<String> many = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l",
                "m", "n");

        assertThat(diff(base(), withTechnologies(base(), many))).singleElement()
                .satisfies(change -> {
                    assertThat(change.computedValue()).contains("+2 more");
                    assertThat(change.computedValue()).hasSizeLessThanOrEqualTo(
                            RequirementFieldChange.MAX_VALUE_LENGTH);
                });
    }

    /**
     * A new persisted component must not be able to slip past the diff unnoticed, so the enum is
     * pinned to the record's own components.
     */
    @Test
    void requirementFieldCoversEveryPersistedRecordComponent() {
        List<String> components = java.util.Arrays.stream(
                        ExtractedRequirements.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .map(RequirementDiffTest::screamingSnake)
                .toList();

        assertThat(java.util.Arrays.stream(RequirementField.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(components);
    }

    private static String screamingSnake(String camelCase) {
        return camelCase.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private ExtractedRequirements base() {
        return new ExtractedRequirements("JUNIOR", false, null, null, false,
                List.of("Java"), List.of("Java"), List.of(), "Bucharest",
                "Romania eligible", List.of(), null, null, null, "DETERMINISTIC");
    }

    private ExtractedRequirements withSeniority(ExtractedRequirements value, String seniority) {
        return new ExtractedRequirements(seniority, value.internshipOrTrainee(),
                value.requiredExperienceYears(), value.requiredEducation(),
                value.finalYearMandatory(), value.technologies(), value.programmingLanguages(),
                value.spokenLanguages(), value.location(), value.remoteEligibility(),
                value.mentorshipSignals(), value.workAuthorization(), value.salary(),
                value.applicationDeadline(), value.extractionMethod());
    }

    private ExtractedRequirements withTechnologies(ExtractedRequirements value,
                                                   List<String> technologies) {
        return new ExtractedRequirements(value.seniority(), value.internshipOrTrainee(),
                value.requiredExperienceYears(), value.requiredEducation(),
                value.finalYearMandatory(), technologies, value.programmingLanguages(),
                value.spokenLanguages(), value.location(), value.remoteEligibility(),
                value.mentorshipSignals(), value.workAuthorization(), value.salary(),
                value.applicationDeadline(), value.extractionMethod());
    }

    private ExtractedRequirements withEducation(ExtractedRequirements value, String education) {
        return new ExtractedRequirements(value.seniority(), value.internshipOrTrainee(),
                value.requiredExperienceYears(), education,
                value.finalYearMandatory(), value.technologies(), value.programmingLanguages(),
                value.spokenLanguages(), value.location(), value.remoteEligibility(),
                value.mentorshipSignals(), value.workAuthorization(), value.salary(),
                value.applicationDeadline(), value.extractionMethod());
    }

    /** The diff is a private implementation detail; the test reaches it without widening it. */
    private static final class ReflectionTestSupport {
        @SuppressWarnings("unchecked")
        static List<RequirementFieldChange> requirementDiff(ScoreRescorePreviewService service,
                                                            ExtractedRequirements stored,
                                                            ExtractedRequirements computed) {
            try {
                Method method = ScoreRescorePreviewService.class.getDeclaredMethod(
                        "requirementDiff", ExtractedRequirements.class,
                        ExtractedRequirements.class);
                method.setAccessible(true);
                return (List<RequirementFieldChange>) method.invoke(service, stored, computed);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not invoke requirementDiff", failure);
            }
        }
    }
}
