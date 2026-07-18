package com.jobpilot.manualurl.parse;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.manualurl.domain.ManualSourceClassification;
import com.jobpilot.manualurl.fetch.ManualFetchedResource;
import com.jobpilot.support.TestProperties;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeterministicManualJobParserTest {
    private static final URI URL = URI.create("https://public.example/jobs/42");
    private static final String DESCRIPTION = "Build and maintain Java services with Spring Boot, SQL, "
            + "testing, code review, and structured engineering mentorship.";

    private final DeterministicManualJobParser parser = new DeterministicManualJobParser(
            new ObjectMapper(), new UrlCanonicalizer(), TestProperties.create());

    @Test
    void parsesJsonLdJobPostingObjectAndSanitizesHtml() {
        String json = jobPosting("\"url\":\"/jobs/42?utm_source=feed\",")
                .replace(DESCRIPTION, "<p>" + DESCRIPTION + "</p>");

        ManualParseResult result = parser.parse(html(script(json)));

        assertThat(result.status()).isEqualTo(ManualParseStatus.SUCCESS);
        assertThat(result.vacancy().sourceClassification())
                .isEqualTo(ManualSourceClassification.SCHEMA_ORG_JOB_POSTING);
        assertThat(result.vacancy().source()).isEqualTo("manual:public.example");
        assertThat(result.vacancy().canonicalUrl()).isEqualTo("https://public.example/jobs/42");
        assertThat(result.vacancy().title()).isEqualTo("Java Developer Intern");
        assertThat(result.vacancy().company()).isEqualTo("Example Company");
        assertThat(result.vacancy().description()).doesNotContain("<p>").contains("Spring Boot");
        assertThat(result.vacancy().location()).contains("Bucharest", "Romania");
        assertThat(result.vacancy().externalId()).isEqualTo("job-42");
        assertThat(result.vacancy().publishedAt())
                .as("bare LocalDate values are normalized to UTC midnight")
                .isEqualTo(Instant.parse("2026-07-18T00:00:00Z"));
    }

    @Test
    void parsesJsonLdArray() {
        String json = "[{\"@type\":\"Organization\",\"name\":\"Other\"},"
                + jobPosting("") + "]";

        assertThat(parser.parse(html(script(json))).status()).isEqualTo(ManualParseStatus.SUCCESS);
    }

    @Test
    void parsesJsonLdGraphAndMultipleScriptBlocks() {
        String json = "{\"@context\":\"https://schema.org\",\"@graph\":["
                + "{\"@type\":\"WebPage\"}," + jobPosting("") + "]}";

        ManualParseResult result = parser.parse(html(
                script("{\"@type\":\"Organization\"}") + script(json)));

        assertThat(result.status()).isEqualTo(ManualParseStatus.SUCCESS);
    }

    @Test
    void parsesHtmlEscapedJsonLd() {
        String escaped = jobPosting("").replace("\"", "&quot;");

        assertThat(parser.parse(html(script(escaped))).status()).isEqualTo(ManualParseStatus.SUCCESS);
    }

    @Test
    void malformedJsonLdReturnsParseFailed() {
        ManualParseResult result = parser.parse(html(
                "<script type='application/ld+json'>{\"@type\":\"JobPosting\",bad}</script>"));

        assertThat(result.status()).isEqualTo(ManualParseStatus.PARSE_FAILED);
    }

    @Test
    void missingRequiredJsonLdFieldsReturnsParseFailed() {
        ManualParseResult result = parser.parse(html(script(
                "{\"@type\":\"JobPosting\",\"title\":\"Intern\"}")));

        assertThat(result.status()).isEqualTo(ManualParseStatus.PARSE_FAILED);
    }

    @Test
    void parsesSupportedMetadataFallback() {
        String body = """
                <html><head>
                  <meta property="og:title" content="Backend Developer Intern">
                  <meta property="og:site_name" content="Example Company">
                  <meta name="job:location" content="Bucharest, Romania">
                  <link rel="canonical" href="/jobs/42?utm_campaign=test">
                </head><body>
                  <main data-job-description><h2>Job description</h2><p>%s</p></main>
                  <a>Apply for this job</a>
                </body></html>
                """.formatted(DESCRIPTION);

        ManualParseResult result = parser.parse(html(body));

        assertThat(result.status()).isEqualTo(ManualParseStatus.SUCCESS);
        assertThat(result.vacancy().sourceClassification())
                .isEqualTo(ManualSourceClassification.SUPPORTED_HTML_METADATA);
        assertThat(result.vacancy().canonicalUrl()).isEqualTo("https://public.example/jobs/42");
    }

    @Test
    void classifiesLoginAndChallengePagesAsProtected() {
        String challenge = "<html><title>Verify you are human</title><body>"
                + "<form action='/login'><input type='password'></form></body></html>";

        assertThat(parser.parse(html(challenge)).status())
                .isEqualTo(ManualParseStatus.BLOCKED_OR_PROTECTED);
    }

    @Test
    void validJobPostingWithRecaptchaBadgeParsesSuccessfully() {
        String body = script(jobPosting(""))
                + "<div class='g-recaptcha-badge'>protected by reCAPTCHA</div>";

        assertThat(parser.parse(html(body)).status()).isEqualTo(ManualParseStatus.SUCCESS);
    }

    @Test
    void detectsStrongChallengeMarkerAfterTwentyThousandCharacters() {
        String challenge = "<html><body><p>" + "x".repeat(21_000)
                + "</p><p>Verify you are human</p></body></html>";

        assertThat(parser.parse(html(challenge)).status())
                .isEqualTo(ManualParseStatus.BLOCKED_OR_PROTECTED);
    }

    @Test
    void weakProtectedPhraseInsideLegitimateVacancyDoesNotBlockParsing() {
        String legitimateDescription = "Build access-control services and investigate access denied "
                + "errors while maintaining Java and Spring Boot applications.";
        String json = jobPosting("").replace(DESCRIPTION, legitimateDescription);

        assertThat(parser.parse(html(script(json))).status()).isEqualTo(ManualParseStatus.SUCCESS);
    }

    @Test
    void genericMarketingPageCannotUseOgSiteNameAsJobCompany() {
        String marketing = """
                <html><head>
                  <meta property="og:title" content="Explore technology jobs">
                  <meta property="og:site_name" content="Example Company">
                </head><body><main>
                  <p>Explore job opportunities, responsibilities, qualifications, roles and positions.</p>
                  <p>Apply your skills across our products and learn about life at our company.</p>
                </main></body></html>
                """;

        assertThat(parser.parse(html(marketing)).status()).isNotEqualTo(ManualParseStatus.SUCCESS);
    }

    @Test
    void genericPublicPageIsUnsupported() {
        assertThat(parser.parse(html("<html><h1>Company news</h1><p>Quarterly update.</p></html>"))
                .status()).isEqualTo(ManualParseStatus.UNSUPPORTED_SOURCE);
    }

    private ManualFetchedResource html(String body) {
        return new ManualFetchedResource(URL, URL, "text/html", body);
    }

    private String script(String json) {
        return "<script type='application/ld+json'>" + json + "</script>";
    }

    private String jobPosting(String additionalFields) {
        return "{" + additionalFields
                + "\"@context\":\"https://schema.org\","
                + "\"@type\":\"JobPosting\","
                + "\"title\":\"Java Developer Intern\","
                + "\"hiringOrganization\":{\"@type\":\"Organization\","
                + "\"name\":\"Example Company\"},"
                + "\"identifier\":{\"value\":\"job-42\"},"
                + "\"jobLocation\":{\"address\":{\"addressLocality\":\"Bucharest\","
                + "\"addressCountry\":\"Romania\"}},"
                + "\"employmentType\":[\"INTERN\",\"FULL_TIME\"],"
                + "\"datePosted\":\"2026-07-18\","
                + "\"validThrough\":\"2026-08-18T23:59:59Z\","
                + "\"description\":\"" + DESCRIPTION + "\"}";
    }
}
