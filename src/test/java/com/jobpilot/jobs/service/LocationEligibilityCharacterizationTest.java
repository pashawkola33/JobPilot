package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.jobs.domain.ScreeningReason;
import com.jobpilot.support.TestProperties;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Locks in the complete observable output of location screening so the Phase 3.2.6
 * performance refactor cannot silently change a decision.
 *
 * <p>Each fixture asserts the whole externally visible result — disposition, eligibility,
 * remote scope, workplace type, normalized city/country, reason text, reason codes and
 * their order, and detected restrictions — not just the disposition. Written and run
 * against the pre-refactor implementation first, then re-run unchanged afterwards.
 */
class LocationEligibilityCharacterizationTest {
    private final LocationEligibilityService service =
            new LocationEligibilityService(TestProperties.create());

    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("bucharest-onsite", raw("Bucharest, Romania", "Java role")),
                Arguments.of("bucharest-diacritics", raw("București, România", "Java role")),
                Arguments.of("bucharest-mixed-case", raw("  BUCHAREST  ,   romania ", "Java role")),
                Arguments.of("bucharest-hybrid", raw("București — Hybrid", "Java role")),
                Arguments.of("bucharest-remote",
                        raw("Bucharest", "Fully remote role", "remote", List.of())),
                Arguments.of("romania-remote", remote("Romania")),
                Arguments.of("eu-remote", remote("European Union")),
                Arguments.of("eea-remote", remote("EEA")),
                Arguments.of("emea-remote", remote("EMEA")),
                Arguments.of("remote-europe", remote("Europe")),
                Arguments.of("europe-timezone",
                        raw("Remote", "Fully remote role. Europe timezone required.")),
                Arguments.of("worldwide-remote",
                        raw("Remote", "Fully remote role. Work from anywhere.")),
                Arguments.of("us-only-remote", raw("Remote", "Fully remote. US only.")),
                Arguments.of("canada-only-remote", raw("Remote", "Fully remote. Canada only.")),
                Arguments.of("uk-only-remote", raw("Remote", "Fully remote. UK residents only.")),
                Arguments.of("germany-only-remote",
                        raw("Remote", "Fully remote. Applicants must be located in Germany.")),
                Arguments.of("apac-only-remote", raw("Remote", "Fully remote. APAC only.")),
                Arguments.of("country-allowlist", raw("Remote", "Fully remote role", "remote",
                        List.of("Romania", "Poland", "Germany"))),
                Arguments.of("country-exclusion",
                        raw("Remote", "Fully remote across Europe, excluding the United States.")),
                Arguments.of("timezone-restriction", raw("Remote - Europe", "Fully remote role",
                        "remote", List.of(), "PST", null)),
                Arguments.of("work-authorization", raw("Remote - Europe", "Fully remote role",
                        "remote", List.of(), null, "United States work authorization required")),
                Arguments.of("onsite-cluj", raw("Cluj-Napoca", "Java role")),
                Arguments.of("onsite-london", raw("London", "Java role")),
                Arguments.of("hybrid-berlin", raw("Berlin — Hybrid", "Java role")),
                Arguments.of("onsite-new-york", raw("New York, NY", "Java role")),
                Arguments.of("office-attendance",
                        raw("Remote", "Fully remote with monthly attendance at the Berlin office required.")),
                Arguments.of("ambiguous-remote", raw("Remote", "Java role")),
                Arguments.of("remote-noise",
                        raw("", "Remote interviews and remote onboarding are available.")),
                Arguments.of("temporary-remote",
                        raw("Remote", "Remote until further notice; office work resumes later.")),
                Arguments.of("structured-contradiction",
                        raw("Remote", "This is a hybrid role in Berlin.", "remote", List.of())),
                Arguments.of("description-contradiction",
                        raw("Berlin", "This role is located in Bucharest.")),
                Arguments.of("many-country-names", raw("Remote",
                        "We serve customers in Germany, France, Spain, Italy, Japan, Brazil, "
                                + "India, Canada, Mexico, Australia, Norway, Sweden and Chile. "
                                + "The role itself is fully remote across Europe.")),
                Arguments.of("misleading-substrings", raw("Remote",
                        "Our Ukraine-adjacent teams use Kubernetes and Grafana. "
                                + "Fully remote role open to Europe.")),
                Arguments.of("punctuation-whitespace",
                        raw("Remote   -    Europe", "Fully   remote\t\trole.\n\nOpen to Europe.")),
                Arguments.of("null-location", raw(null, "Fully remote role open to Europe")),
                Arguments.of("blank-fields", raw("", "")),
                Arguments.of("null-description", raw("Bucharest", null)),
                Arguments.of("ilfov", raw("Ilfov, Romania", "Java role")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void completeObservableResultIsStable(String name, RawJob raw) {
        LocationEligibilityDecision decision = service.evaluate(raw);

        assertThat(snapshot(decision))
                .as("characterization snapshot for %s", name)
                .isEqualTo(EXPECTED.get(name));
    }

    @Test
    void everyFixtureHasARecordedExpectation() {
        List<String> names = fixtures().map(argument -> (String) argument.get()[0]).toList();

        assertThat(names).doesNotHaveDuplicates();
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrderElementsOf(names);
    }

    /** Full externally observable projection, including reason-code ordering. */
    static String snapshot(LocationEligibilityDecision decision) {
        String reasons = decision.reasons().stream()
                .map(reason -> reason.stage() + "/" + reason.code() + "/" + reason.message())
                .reduce((left, right) -> left + " >> " + right).orElse("");
        return String.join("\n",
                "disposition=" + decision.disposition(),
                "eligibility=" + decision.locationEligibility(),
                "workplace=" + decision.workplaceType(),
                "scope=" + decision.remoteScope(),
                "city=" + decision.normalizedCity(),
                "country=" + decision.normalizedCountry(),
                "eligibleFromRomania=" + decision.eligibleFromRomania(),
                "reason=" + decision.eligibilityReason(),
                "restrictions=" + decision.detectedLocationRestrictions(),
                "timezone=" + decision.requiredTimezone(),
                "authorization=" + decision.requiredWorkAuthorization(),
                "reasons=" + reasons);
    }

    private static RawJob remote(String scope) {
        return raw("Remote - " + scope, "Fully remote role open to " + scope);
    }

    private static RawJob raw(String location, String description) {
        return raw(location, description, null, List.of());
    }

    private static RawJob raw(String location, String description, String workplaceType,
                              List<String> applicantLocations) {
        return raw(location, description, workplaceType, applicantLocations, null, null);
    }

    private static RawJob raw(String location, String description, String workplaceType,
                              List<String> applicantLocations, String timezone,
                              String authorization) {
        String key = String.valueOf(location) + String.valueOf(description);
        return new RawJob("fixture", Integer.toHexString(key.hashCode()),
                "https://example.com/jobs/" + Integer.toUnsignedString(key.hashCode()),
                "Java Developer", "Example", location, description, "Full-time", null, null,
                "fixture", new RawLocationData(workplaceType,
                location == null ? List.of() : List.of(location), List.of(), null,
                applicantLocations, timezone, authorization));
    }

    /** Populated from the pre-refactor implementation; see EXPECTED_SNAPSHOTS below. */
    private static final java.util.Map<String, String> EXPECTED = ExpectedLocationSnapshots.ALL;

    /** Reason type is referenced so an accidental record change breaks compilation. */
    @SuppressWarnings("unused")
    private static final Class<ScreeningReason> REASON_TYPE = ScreeningReason.class;
}
