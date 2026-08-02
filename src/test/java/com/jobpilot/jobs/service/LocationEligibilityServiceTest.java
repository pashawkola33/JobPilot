package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.LocationEligibility;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.jobs.domain.RemoteScope;
import com.jobpilot.jobs.domain.WorkplaceType;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.support.TestProperties;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class LocationEligibilityServiceTest {
    private final LocationEligibilityService service =
            new LocationEligibilityService(TestProperties.create());

    @ParameterizedTest
    @MethodSource("accepted")
    void acceptsOnlyBucharestLocalOrRomaniaCompatibleRemote(
            RawJob raw, WorkplaceType workplace, LocationEligibility eligibility, RemoteScope scope) {
        var decision = service.evaluate(raw);

        assertThat(decision.workplaceType()).isEqualTo(workplace);
        assertThat(decision.locationEligibility()).isEqualTo(eligibility);
        assertThat(decision.remoteScope()).isEqualTo(scope);
        assertThat(decision.eligibleFromRomania()).isTrue();
        assertThat(decision.accepted()).isTrue();
        assertThat(decision.eligibilityReason()).isNotBlank();
        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(decision.reasons()).isNotEmpty();
    }

    static Stream<Arguments> accepted() {
        return Stream.of(
                Arguments.of(raw("Bucharest", "Java role"), WorkplaceType.ONSITE,
                        LocationEligibility.BUCHAREST_LOCAL, RemoteScope.UNKNOWN),
                Arguments.of(raw("București — Hybrid", "Java role"), WorkplaceType.HYBRID,
                        LocationEligibility.BUCHAREST_LOCAL, RemoteScope.UNKNOWN),
                Arguments.of(raw("Bucharest", "Fully remote role", "remote", List.of()),
                        WorkplaceType.REMOTE, LocationEligibility.BUCHAREST_LOCAL, RemoteScope.ROMANIA),
                Arguments.of(remote("Romania"), WorkplaceType.REMOTE,
                        LocationEligibility.REMOTE_ROMANIA_ELIGIBLE, RemoteScope.ROMANIA),
                Arguments.of(remote("European Union"), WorkplaceType.REMOTE,
                        LocationEligibility.REMOTE_ROMANIA_ELIGIBLE, RemoteScope.EU),
                Arguments.of(remote("Europe"), WorkplaceType.REMOTE,
                        LocationEligibility.REMOTE_ROMANIA_ELIGIBLE, RemoteScope.EUROPE),
                Arguments.of(remote("EEA"), WorkplaceType.REMOTE,
                        LocationEligibility.REMOTE_ROMANIA_ELIGIBLE, RemoteScope.EEA),
                Arguments.of(remote("EMEA"), WorkplaceType.REMOTE,
                        LocationEligibility.REMOTE_ROMANIA_ELIGIBLE, RemoteScope.EMEA),
                Arguments.of(raw("Remote", "Fully remote role. Work from anywhere."),
                        WorkplaceType.REMOTE, LocationEligibility.REMOTE_ROMANIA_ELIGIBLE,
                        RemoteScope.WORLDWIDE));
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejectsOutOfMarketLocalAndRestrictedRemoteJobs(RawJob raw, WorkplaceType workplace) {
        var decision = service.evaluate(raw);

        assertThat(decision.workplaceType()).isEqualTo(workplace);
        assertThat(decision.locationEligibility()).isEqualTo(LocationEligibility.REJECTED_LOCATION);
        assertThat(decision.eligibleFromRomania()).isFalse();
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.eligibilityReason()).isNotBlank();
        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REJECT);
    }

    static Stream<Arguments> rejected() {
        return Stream.of(
                Arguments.of(raw("Cluj-Napoca", "Java role"), WorkplaceType.ONSITE),
                Arguments.of(raw("Berlin — Hybrid", "Java role"), WorkplaceType.HYBRID),
                Arguments.of(raw("London", "Java role"), WorkplaceType.ONSITE),
                Arguments.of(raw("Remote", "Fully remote. Remote within Germany."), WorkplaceType.REMOTE),
                Arguments.of(raw("Remote", "Fully remote. US only."), WorkplaceType.REMOTE),
                Arguments.of(raw("Remote", "Fully remote. UK residents only."), WorkplaceType.REMOTE),
                Arguments.of(raw("Remote", "Fully remote. Canada only."), WorkplaceType.REMOTE),
                Arguments.of(raw("Remote", "Fully remote. Switzerland only."), WorkplaceType.REMOTE),
                Arguments.of(raw("Remote", "Fully remote. APAC only."), WorkplaceType.REMOTE),
                Arguments.of(raw("Remote", "Fully remote. Candidates must reside in the United States."),
                        WorkplaceType.REMOTE),
                Arguments.of(raw("Remote", "Fully remote. Applicants must be located in Germany."),
                        WorkplaceType.REMOTE),
                Arguments.of(raw("USA | Remote", "Build backend services."), WorkplaceType.REMOTE),
                Arguments.of(raw("Remote - US", "Build backend services."), WorkplaceType.REMOTE),
                Arguments.of(raw("San Francisco, California", "Build backend services.",
                        "remote", List.of()), WorkplaceType.REMOTE),
                Arguments.of(raw("Hybrid (UK)", "Build backend services."), WorkplaceType.HYBRID),
                Arguments.of(raw("London", "Hybrid engineering role.", "hybrid", List.of()),
                        WorkplaceType.HYBRID),
                Arguments.of(raw("Remote", "Fully remote with monthly attendance at the Berlin office required."),
                        WorkplaceType.REMOTE),
                Arguments.of(raw("Remote - Europe", "Fully remote role", "remote", List.of(),
                        "PST", null), WorkplaceType.REMOTE),
                Arguments.of(raw("Remote - Europe", "Fully remote role", "remote", List.of(),
                        null, "United States work authorization required"), WorkplaceType.REMOTE));
    }

    @ParameterizedTest
    @MethodSource("unknown")
    void leavesAmbiguousOrContradictoryRemoteEligibilityUnknown(RawJob raw) {
        var decision = service.evaluate(raw);

        assertThat(decision.locationEligibility())
                .isEqualTo(LocationEligibility.REMOTE_ELIGIBILITY_UNKNOWN);
        assertThat(decision.eligibleFromRomania()).isFalse();
        assertThat(decision.accepted()).isFalse();
        assertThat(decision.processable()).isTrue();
        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REVIEW);
    }

    static Stream<RawJob> unknown() {
        return Stream.of(
                raw("Remote", "Java role"),
                raw("", "Remote interviews and remote onboarding are available."),
                raw("", "Java role"),
                raw("Remote", "This is a hybrid role in Berlin.", "remote", List.of()),
                raw("Berlin", "This role is located in Bucharest."),
                raw("Remote", "Join our global team serving European customers."),
                raw("Remote - Europe Timezone", "Collaborate during European working hours.",
                        "remote", List.of()),
                raw("Remote", "Remote until further notice; office work resumes later."));
    }

    @Test
    void acceptsEuropeTimezoneLabelOnlyWithAuthoritativeApplicantScope() {
        var decision = service.evaluate(raw("Remote - Europe Timezone",
                "Collaborate during European working hours.", "remote", List.of("Europe")));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(decision.remoteScope()).isEqualTo(RemoteScope.EUROPE);
    }

    @Test
    void reviewsContradictoryUsLocationAndWorldwideApplicantScope() {
        var decision = service.evaluate(raw("San Francisco, California",
                "Fully remote engineering role.", "remote", List.of("Worldwide")));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(decision.reasons()).extracting(reason -> reason.code())
                .containsExactly("LOCATION_SCOPE_CONTRADICTION");
        assertThat(decision.eligibilityReason()).contains("contradicts", "worldwide");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Developer Relations Engineer (San Francisco, CA)",
            "Backend Engineer (New York, NY)",
            "Backend Engineer (London, UK)",
            "Backend Engineer - Berlin, Germany",
            "Backend Engineer Austin, TX",
            "Backend Engineer US Only",
            "Backend Engineer UK Only"
    })
    void rejectsStructuredIncompatibleLocationRestrictionsInTitle(String title) {
        var decision = service.evaluate(raw(title, "Example", "Remote",
                "Build backend services.", "remote", List.of()));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(decision.locationEligibility()).isEqualTo(LocationEligibility.REJECTED_LOCATION);
        assertThat(decision.eligibilityReason()).contains("title location", "Romania");
        assertThat(decision.detectedLocationRestrictions())
                .anyMatch(restriction -> restriction.startsWith("Title location:"));
    }

    @Test
    void reviewsTitleLocationContradictedByWorldwideRemoteLocationField() {
        var decision = service.evaluate(raw("Backend Engineer (Austin, TX)", "Example",
                "Worldwide Remote", "Build backend services.", "remote", List.of()));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(decision.reasons()).extracting(reason -> reason.code())
                .containsExactly("LOCATION_SCOPE_CONTRADICTION");
        assertThat(decision.eligibilityReason()).contains("title location", "worldwide");
    }

    @Test
    void ignoresUnstructuredLocationWordsAndNonLocationParentheticalTitleText() {
        assertThat(service.evaluate(raw("Austin Database Engineer", "Berlin Systems",
                "Remote", "Implement Java services using Spring (Framework).",
                "remote", List.of())).disposition())
                .isEqualTo(ScreeningDisposition.REVIEW);

        assertThat(service.evaluate(raw("Java Engineer (Spring)", "London Technologies",
                "Remote", "Build services for the Austin product line.",
                "remote", List.of())).disposition())
                .isEqualTo(ScreeningDisposition.REVIEW);
    }

    @ParameterizedTest
    @MethodSource("bucharestSpellings")
    void normalizesBucharestSpellings(String location) {
        var decision = service.evaluate(raw(location, "Java role"));

        assertThat(decision.locationEligibility()).isEqualTo(LocationEligibility.BUCHAREST_LOCAL);
        assertThat(decision.normalizedCity()).isEqualTo("Bucharest");
        assertThat(decision.normalizedCountry()).isEqualTo("Romania");
    }

    static Stream<String> bucharestSpellings() {
        return Stream.of("Bucharest", "Bucuresti", "București", "Bucharest, Romania",
                "București, România", "Bucharest Metropolitan Area");
    }

    @Test
    void keepsIlfovSeparateUnlessExplicitlyEnabled() {
        var rejected = service.evaluate(raw("Ilfov, Romania", "Java role"));
        assertThat(rejected.locationEligibility()).isEqualTo(LocationEligibility.REJECTED_LOCATION);
        assertThat(rejected.normalizedCity()).isEqualTo("Ilfov");

        JobPilotProperties.Eligibility enabledSettings = new JobPilotProperties.Eligibility(
                "Bucharest", "Romania", true, true, true, true, true, true,
                List.of(RemoteScope.ROMANIA, RemoteScope.EU, RemoteScope.EEA,
                        RemoteScope.EUROPE, RemoteScope.EMEA, RemoteScope.WORLDWIDE));
        var accepted = new LocationEligibilityService(enabledSettings)
                .evaluate(raw("Ilfov, Romania", "Java role"));
        assertThat(accepted.locationEligibility()).isEqualTo(LocationEligibility.BUCHAREST_LOCAL);
        assertThat(accepted.normalizedCity()).isEqualTo("Ilfov");
    }

    private static RawJob remote(String scope) {
        return raw("Remote - " + scope, "Fully remote role open to " + scope);
    }

    private static RawJob raw(String location, String description) {
        return raw(location, description, null, List.of());
    }

    private static RawJob raw(String location, String description,
                              String workplaceType, List<String> applicantLocations) {
        return raw(location, description, workplaceType, applicantLocations, null, null);
    }

    private static RawJob raw(String location, String description, String workplaceType,
                              List<String> applicantLocations, String timezone, String authorization) {
        return new RawJob("fixture", Integer.toHexString((location + description).hashCode()),
                "https://example.com/jobs/" + Integer.toUnsignedString((location + description).hashCode()),
                "Java Developer", "Example", location, description, "Full-time", null, null,
                "fixture", new RawLocationData(workplaceType, List.of(location), List.of(), null,
                applicantLocations, timezone, authorization));
    }

    private static RawJob raw(String title, String company, String location, String description,
                              String workplaceType, List<String> applicantLocations) {
        String identity = title + company + location + description;
        return new RawJob("fixture", Integer.toHexString(identity.hashCode()),
                "https://example.com/jobs/" + Integer.toUnsignedString(identity.hashCode()),
                title, company, location, description, "Full-time", null, null,
                "fixture", new RawLocationData(workplaceType, List.of(location), List.of(), null,
                applicantLocations, null, null));
    }
}
