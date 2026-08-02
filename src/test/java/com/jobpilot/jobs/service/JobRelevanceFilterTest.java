package com.jobpilot.jobs.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.support.TestProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobRelevanceFilterTest {
    private final JobRelevanceFilter filter = new JobRelevanceFilter(TestProperties.create());

    @Test
    void matchesSemanticInternshipWithoutConfiguredExactPhrase() {
        var decision = filter.evaluate(raw("Software Engineering Internship",
                "Backend services, REST APIs, SQL, and Java."));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(decision.reasons()).isNotEmpty();
    }

    @Test
    void reviewsGeneralSoftwareInternshipWithoutJava() {
        assertThat(filter.evaluate(raw("Software Engineering Internship",
                "General software development using Python and cloud services.")).disposition())
                .isEqualTo(ScreeningDisposition.REVIEW);
    }

    @Test
    void matchesJuniorJavaBackendRole() {
        assertThat(filter.evaluate(raw("Junior Backend Engineer",
                "Java, Spring Boot, PostgreSQL, REST APIs.")).disposition())
                .isEqualTo(ScreeningDisposition.MATCH);
    }

    @Test
    void reviewsBackendRoleWhenLanguageAndTechnologyAreUnspecified() {
        assertThat(filter.evaluate(raw("Graduate Backend Engineer",
                "Build and maintain product services.")).disposition())
                .isEqualTo(ScreeningDisposition.REVIEW);
    }

    @Test
    void reviewsRelevantTechnologyWhenRoleTitleIsAmbiguous() {
        assertThat(filter.evaluate(raw("Technology Specialist",
                "Work with Java, Spring, REST APIs, and PostgreSQL.")).disposition())
                .isEqualTo(ScreeningDisposition.REVIEW);
    }

    @Test
    void rejectsSalesRoleEvenWhenTechnicalKeywordsArePresent() {
        var decision = filter.evaluate(raw("Pre-Sales Solutions Engineer",
                "Java APIs and cloud integrations."));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(decision.reasons()).extracting(reason -> reason.code())
                .containsExactly("SALES_ADJACENT_TECHNICAL_ROLE");
    }

    @Test
    void usesWordBoundariesForJavaAndInternSignals() {
        assertThat(filter.evaluate(raw("JavaScript Developer",
                "Build frontend applications and collaborate internally.")).disposition())
                .isEqualTo(ScreeningDisposition.REJECT);
    }

    @Test
    void rejectsRevenueAccountantDespiteSqlAndApiText() {
        assertNonEngineering("Revenue Accountant",
                "Own revenue accounting systems, SQL reports, APIs, and cloud integrations.");
    }

    @Test
    void rejectsGovernanceRiskAndComplianceInternDespiteTechnicalSystems() {
        assertNonEngineering("Governance, Risk, and Compliance Intern",
                "Review technical systems, backend controls, cloud APIs, and SQL evidence.");
    }

    @Test
    void rejectsDeveloperMarketerDespiteDeveloperToolKeywords() {
        assertNonEngineering("Developer Marketer",
                "Market Java APIs, backend systems, integrations, cloud, and developer tools.");
    }

    @Test
    void rejectsSolutionsEngineerDominatedByDemosAndSalesOwnership() {
        var decision = filter.evaluate(raw("Solutions Engineer",
                "Own product demos for prospective customers, support the sales cycle, and carry "
                        + "solution-selling targets."));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(decision.reasons()).extracting(reason -> reason.code())
                .containsExactly("SALES_ADJACENT_TECHNICAL_ROLE");
    }

    @Test
    void matchesSolutionsEngineerWithDecisiveHandsOnJavaImplementation() {
        var decision = filter.evaluate(raw("Solutions Engineer",
                "Design and implement Java and Spring Boot backend services. Write code, test code, "
                        + "and deploy production API integrations."));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.MATCH);
        assertThat(decision.reasons()).extracting(reason -> reason.code())
                .containsExactly("SOFTWARE_DEVELOPMENT_ROLE");
    }

    @Test
    void reviewsAiPlatformEngineerWithoutJavaOrBackendEvidence() {
        var decision = filter.evaluate(raw("AI Platform Engineer",
                "Build model evaluation infrastructure using Python and cloud services."));

        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REVIEW);
        assertThat(decision.reasons()).extracting(reason -> reason.code())
                .containsExactly("SOFTWARE_DEVELOPMENT_ROLE");
    }

    @Test
    void unrelatedTitleCannotBePromotedByEngineeringCompanyBoilerplate() {
        assertNonEngineering("Internal Auditor",
                "Our engineering organization builds Java backend services, REST APIs, SQL systems, "
                        + "and developer tooling in the cloud.");
    }

    @Test
    void rejectsBrandDesignerDespiteSqlAndApiText() {
        assertNonEngineering("Brand Designer",
                "Create brand campaigns while using SQL dashboards and internal APIs.");
    }

    @Test
    void rejectsGrowthAndDataRoleDespiteAnalyticsAndDatabaseText() {
        assertNonEngineering("Growth & Data (IC)",
                "Own growth analytics, database reporting, experiments, and SQL metrics.");
    }

    @Test
    void reviewsGrowthAndDataWhenTitleExplicitlyEstablishesSoftwareEngineering() {
        assertThat(filter.evaluate(raw("Growth & Data Software Engineer",
                "Own growth experiments and analytics."
        )).disposition()).isEqualTo(ScreeningDisposition.REVIEW);
    }

    @Test
    void rejectsQuantRiskRoleDespitePythonSqlAndBackendText() {
        assertNonEngineering("Quant - Risk | Propr.xyz",
                "Use Python, SQL, backend APIs, and databases for quantitative risk models.");
    }

    @Test
    void rejectsDeveloperRelationsWhenAdvocacyIsPrimaryDespiteApiAndSdkText() {
        assertNonEngineering("Developer Relations Engineer",
                "Lead developer advocacy, community events, SDK education, and API workshops.");
    }

    @Test
    void reviewsDeveloperRelationsWithExclusiveProductionImplementationOwnership() {
        assertThat(filter.evaluate(raw("Developer Relations Engineer",
                "Write code and implement backend services; deploy production API integrations."
        )).disposition()).isEqualTo(ScreeningDisposition.REVIEW);
    }

    @Test
    void reviewsDesignEngineerImplementingReactComponents() {
        assertThat(filter.evaluate(raw("Design Engineer",
                "Implement React components in TypeScript and ship tested user interfaces."
        )).disposition()).isEqualTo(ScreeningDisposition.REVIEW);
    }

    @Test
    void rejectsDesignEngineerWhenVisualProductDesignIsPrimary() {
        assertNonEngineering("Design Engineer",
                "Own product design, visual assets, typography, and Figma prototypes.");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Postgres Engineer", "Postgres Deployment Engineer", "Deployment Engineer",
            "Release Engineer", "Performance Engineer", "Anti Abuse Engineer",
            "Multigres Engineer"
    })
    void reviewsAmbiguousTechnicalEngineeringTitles(String title) {
        assertThat(filter.evaluate(raw(title,
                "Improve reliability, delivery, runtime behavior, and production operations."
        )).disposition()).isEqualTo(ScreeningDisposition.REVIEW);
    }

    private void assertNonEngineering(String title, String description) {
        var decision = filter.evaluate(raw(title, description));
        assertThat(decision.disposition()).isEqualTo(ScreeningDisposition.REJECT);
        assertThat(decision.reasons()).extracting(reason -> reason.code())
                .containsExactly("NON_ENGINEERING_PRIMARY_FUNCTION");
    }

    private RawJob raw(String title, String description) {
        return new RawJob("fixture", title, "https://example.com/jobs/" + title.hashCode(),
                title, "Example", "Bucharest", description, "Internship",
                null, null, "fixture");
    }
}
