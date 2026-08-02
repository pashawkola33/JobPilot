package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.jobs.domain.ScreeningDecision;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.support.TestProperties;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class LiveAtsPrecisionRegressionTest {
    private final LocationEligibilityService location =
            new LocationEligibilityService(TestProperties.create());
    private final EarlyCareerEligibilityService career = new EarlyCareerEligibilityService();
    private final JobRelevanceFilter relevance = new JobRelevanceFilter(TestProperties.create());

    @ParameterizedTest(name = "{0}")
    @MethodSource("observedFalsePositives")
    void observedLiveFalsePositivesHaveDeterministicStageAndFinalDispositions(
            String fixture, RawJob raw, ScreeningDisposition expectedLocation,
            ScreeningDisposition expectedRelevance, ScreeningDisposition expectedFinal) {
        var locationDecision = location.evaluate(raw);
        var careerDecision = career.evaluate(raw);
        var relevanceDecision = relevance.evaluate(raw);
        var finalDecision = ScreeningDecision.of(
                locationDecision, careerDecision, relevanceDecision);

        assertThat(locationDecision.disposition()).isEqualTo(expectedLocation);
        assertThat(relevanceDecision.disposition()).isEqualTo(expectedRelevance);
        assertThat(finalDecision.disposition()).isEqualTo(expectedFinal);
    }

    static Stream<Arguments> observedFalsePositives() {
        return Stream.of(
                Arguments.of("Algolia Solutions Engineer in Seattle",
                        raw("algolia-solutions", "Algolia", "Solutions Engineer",
                                "Seattle, Washington", "Remote", List.of(),
                                "Run product demos for prospective customers, support the sales cycle, "
                                        + "and own solution-selling targets for developer APIs."),
                        ScreeningDisposition.REJECT, ScreeningDisposition.REJECT,
                        ScreeningDisposition.REJECT),
                Arguments.of("Notion Revenue Accountant in San Francisco",
                        raw("notion-accountant", "Notion", "Revenue Accountant",
                                "San Francisco, California", "Remote", List.of(),
                                "Own revenue accounting systems, SQL reporting, APIs, and cloud tooling."),
                        ScreeningDisposition.REJECT, ScreeningDisposition.REJECT,
                        ScreeningDisposition.REJECT),
                Arguments.of("Notion GRC Intern in San Francisco",
                        raw("notion-grc", "Notion", "Governance, Risk, and Compliance Intern",
                                "San Francisco, California", "Remote", List.of(),
                                "Review backend controls, technical systems, cloud APIs, and SQL evidence."),
                        ScreeningDisposition.REJECT, ScreeningDisposition.REJECT,
                        ScreeningDisposition.REJECT),
                Arguments.of("Deepgram Backend Engineer with USA remote scope",
                        raw("deepgram-backend", "Deepgram", "Backend Engineer",
                                "USA | Remote", "Remote", List.of(),
                                "Build Java backend services, REST APIs, and PostgreSQL integrations."),
                        ScreeningDisposition.REJECT, ScreeningDisposition.MATCH,
                        ScreeningDisposition.REJECT),
                Arguments.of("PostHog Developer Marketer",
                        raw("posthog-marketer", "PostHog", "Developer Marketer",
                                "Remote - Europe", "Remote", List.of("Europe"),
                                "Market developer tools, Java APIs, backend integrations, and cloud systems."),
                        ScreeningDisposition.MATCH, ScreeningDisposition.REJECT,
                        ScreeningDisposition.REJECT),
                Arguments.of("PostHog Hybrid UK engineering role",
                        raw("posthog-hybrid", "PostHog", "Backend Engineer",
                                "Hybrid (UK)", "Hybrid", List.of(),
                                "Build Java and Spring Boot backend services and REST APIs."),
                        ScreeningDisposition.REJECT, ScreeningDisposition.MATCH,
                        ScreeningDisposition.REJECT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secondLiveRunFalsePositives")
    void secondLiveRunFalsePositivesAreNowHardRejectedAtTheExpectedStage(
            String fixture, RawJob raw, ScreeningDisposition expectedLocation,
            ScreeningDisposition expectedRelevance) {
        var locationDecision = location.evaluate(raw);
        var careerDecision = career.evaluate(raw);
        var relevanceDecision = relevance.evaluate(raw);

        assertThat(locationDecision.disposition()).isEqualTo(expectedLocation);
        assertThat(relevanceDecision.disposition()).isEqualTo(expectedRelevance);
        assertThat(ScreeningDecision.of(locationDecision, careerDecision, relevanceDecision)
                .disposition()).isEqualTo(ScreeningDisposition.REJECT);
    }

    static Stream<Arguments> secondLiveRunFalsePositives() {
        return Stream.of(
                Arguments.of("Developer Relations Engineer title-restricted to San Francisco",
                        raw("devrel-san-francisco", "Example", "Developer Relations Engineer "
                                        + "(San Francisco, CA)", "Remote", "Remote", List.of(),
                                "Lead developer advocacy, community events, API education, and SDK "
                                        + "workshops."),
                        ScreeningDisposition.REJECT, ScreeningDisposition.REJECT),
                Arguments.of("Brand Designer",
                        raw("brand-designer", "Example", "Brand Designer", "Remote - Europe",
                                "Remote", List.of("Europe"),
                                "Create brand campaigns using SQL dashboards and internal APIs."),
                        ScreeningDisposition.MATCH, ScreeningDisposition.REJECT),
                Arguments.of("Growth & Data (IC)",
                        raw("growth-data", "Example", "Growth & Data (IC)", "Remote - Europe",
                                "Remote", List.of("Europe"),
                                "Own growth analytics, experiments, SQL metrics, and databases."),
                        ScreeningDisposition.MATCH, ScreeningDisposition.REJECT),
                Arguments.of("Quant - Risk | Propr.xyz",
                        raw("quant-risk", "Propr.xyz", "Quant - Risk | Propr.xyz",
                                "Remote - Europe", "Remote", List.of("Europe"),
                                "Use Python, SQL, backend APIs, and databases for risk models."),
                        ScreeningDisposition.MATCH, ScreeningDisposition.REJECT));
    }

    private static RawJob raw(String id, String company, String title, String location,
                              String workplace, List<String> applicantScope, String description) {
        return new RawJob("live-regression", id, "https://example.com/jobs/" + id,
                title, company, location, description, "Full-time", null, null, id,
                new RawLocationData(workplace, List.of(location), List.of(), null,
                        applicantScope, null, null), company);
    }
}
