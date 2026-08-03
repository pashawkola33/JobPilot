package com.jobpilot.sources.smartrecruiters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.LocationEligibility;
import com.jobpilot.jobs.service.LocationEligibilityService;
import com.jobpilot.sources.health.TenantFailureCategory;
import com.jobpilot.sources.health.TenantFailureClassifier;
import com.jobpilot.support.TestProperties;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmartRecruitersJobSourceTest {
    private static final String COMPANY = "SyntheticCo";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsHydratedPostingAndDeduplicatesCountryAndRemotePartitions() throws Exception {
        JsonNode list = fixture("list-single.json");
        JsonNode detail = fixture("detail-single.json");
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        List<String> requests = new ArrayList<>();
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            requests.add(url);
            return url.contains("/postings/post-1") ? detail : list;
        });

        var jobs = source(http, List.of(COMPANY)).fetchCompany(COMPANY);

        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.source()).isEqualTo("smartrecruiters");
            assertThat(job.externalId()).isEqualTo("post-1");
            assertThat(job.providerTenant()).isEqualTo(COMPANY);
            assertThat(job.company()).isEqualTo("Synthetic Company");
            assertThat(job.title()).isEqualTo("Junior Java Engineer");
            assertThat(job.url()).isEqualTo(
                    "https://careers.example.test/jobs/post-1?language=en");
            assertThat(job.location()).isEqualTo("Bucharest, Bucuresti, RO, Remote");
            assertThat(job.locationData().workplaceType()).isEqualTo("Remote");
            assertThat(job.locationData().structuredLocations())
                    .containsExactly("Bucharest, Bucuresti, RO");
            assertThat(job.locationData().remoteRegions()).isEmpty();
            assertThat(job.employmentType()).isEqualTo("Full-time");
            assertThat(job.careerData().providerSeniority()).isEqualTo("Entry level");
            assertThat(job.publishedAt()).hasToString("2026-07-30T09:15:00Z");
            assertThat(job.description()).isEqualTo("Department: Engineering\n"
                    + "Function: Software Development\n"
                    + "Build Java & Spring services.\n"
                    + "Internship or graduate experience.\nMentoring is provided.");
            assertThat(job.description()).doesNotContain("employer boilerplate", "<p>");
            assertThat(job.rawPayload()).doesNotContain("unusedLiveLikeTree", "mustNot");
        });
        assertThat(requests).containsExactly(
                "https://api.smartrecruiters.com/v1/companies/SyntheticCo/postings"
                        + "?limit=100&offset=0&country=ro",
                "https://api.smartrecruiters.com/v1/companies/SyntheticCo/postings"
                        + "?limit=100&offset=0&q=remote",
                "https://api.smartrecruiters.com/v1/companies/SyntheticCo/postings/post-1");
    }

    @Test
    void paginatesDeterministicallyAndHydratesEachUniqueIdOnce() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        AtomicInteger details = new AtomicInteger();
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (url.contains("/postings/post-")) {
                details.incrementAndGet();
                return detail(COMPANY, url.substring(url.lastIndexOf('/') + 1), "ro", false,
                        "Java graduate role in Bucharest");
            }
            if (url.contains("q=remote")) return page(COMPANY, 0, 0, 0, 0);
            int offset = offset(url);
            return offset == 0 ? page(COMPANY, 0, 101, 100, 0)
                    : page(COMPANY, 100, 101, 1, 100);
        });

        var jobs = source(http, List.of(COMPANY)).fetchCompany(COMPANY);

        assertThat(jobs).hasSize(101);
        assertThat(jobs).extracting(job -> job.externalId())
                .startsWith("post-0", "post-1").endsWith("post-100");
        assertThat(details).hasValue(101);
    }

    @Test
    void emptyPartitionsProduceAnEmptySuccessfulResultWithoutDetailRequests() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.getJson(anyString())).thenReturn(page(COMPANY, 0, 0, 0, 0));

        assertThat(source(http, List.of(COMPANY)).fetchCompany(COMPANY)).isEmpty();
        verify(http).getJson("https://api.smartrecruiters.com/v1/companies/SyntheticCo/postings"
                + "?limit=100&offset=0&country=ro");
        verify(http).getJson("https://api.smartrecruiters.com/v1/companies/SyntheticCo/postings"
                + "?limit=100&offset=0&q=remote");
    }

    @Test
    void laterDetailFailureDiscardsTheCompleteTenantResult() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (url.endsWith("/post-0")) {
                return detail(COMPANY, "post-0", "ro", false, "Java internship");
            }
            if (url.endsWith("/post-1")) {
                throw new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, 500);
            }
            return page(COMPANY, 0, 2, 2, 0);
        });

        assertThat(source(http, List.of(COMPANY)).fetchJobs()).isEmpty();
    }

    @Test
    void repeatedPageFingerprintAndImpossibleTotalsFailAsParseErrors() {
        ExternalHttpClient repeated = mock(ExternalHttpClient.class);
        when(repeated.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            return offset(url) == 0 ? page(COMPANY, 0, 2, 1, 0)
                    : page(COMPANY, 1, 2, 1, 0);
        });
        assertParseFailure(() -> source(repeated, List.of(COMPANY)).fetchCompany(COMPANY));

        ExternalHttpClient inconsistent = mock(ExternalHttpClient.class);
        when(inconsistent.getJson(anyString())).thenReturn(page(COMPANY, 0, 0, 1, 0));
        assertParseFailure(() -> source(inconsistent, List.of(COMPANY)).fetchCompany(COMPANY));
    }

    @Test
    void sharedPageCapAndUniquePostingCapFailClosed() {
        ExternalHttpClient pages = mock(ExternalHttpClient.class);
        when(pages.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            int offset = offset(url);
            return page(COMPANY, offset, 11, 1, offset);
        });
        assertThatThrownBy(() -> source(pages, List.of(COMPANY)).fetchCompany(COMPANY))
                .isInstanceOfSatisfying(SmartRecruitersLimitException.class, failure -> {
                    assertThat(failure.limit())
                            .isEqualTo(SmartRecruitersLimitException.Limit.LIST_PAGES);
                    assertThat(failure.configuredMaximum()).isEqualTo(10);
                });

        ExternalHttpClient jobs = mock(ExternalHttpClient.class);
        when(jobs.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            int offset = offset(url);
            return page(COMPANY, offset, 501, 100, offset);
        });
        assertThatThrownBy(() -> source(jobs, List.of(COMPANY)).fetchCompany(COMPANY))
                .isInstanceOfSatisfying(SmartRecruitersLimitException.class, failure -> {
                    assertThat(failure.limit())
                            .isEqualTo(SmartRecruitersLimitException.Limit.UNIQUE_POSTINGS);
                    assertThat(failure.configuredMaximum()).isEqualTo(500);
                });
    }

    @Test
    void requiredDetailFieldsIdentityDatesLocationsAndUrlsFailClosed() {
        for (String mutation : List.of("id", "description", "date", "location", "url")) {
            ExternalHttpClient http = mock(ExternalHttpClient.class);
            when(http.getJson(anyString())).thenAnswer(invocation -> {
                String url = invocation.getArgument(0);
                if (!url.contains("/postings/post-")) return page(COMPANY, 0, 1, 1, 0);
                ObjectNode detail = detail(COMPANY, "post-0", "ro", false, "Java internship");
                switch (mutation) {
                    case "id" -> detail.put("id", "different");
                    case "description" -> detail.with("jobAd").with("sections")
                            .with("jobDescription").remove("text");
                    case "date" -> detail.put("releasedDate", "not-an-instant");
                    case "location" -> detail.put("location", "Bucharest");
                    default -> detail.put("postingUrl", "javascript:alert(1)");
                }
                return detail;
            });
            assertParseFailure(() -> source(http, List.of(COMPANY)).fetchCompany(COMPANY));
        }
    }

    @Test
    void providerRemoteFlagDoesNotDecideRomaniaEligibility() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (!url.contains("/postings/post-")) return page(COMPANY, 0, 1, 1, 0);
            ObjectNode detail = detail(COMPANY, "post-0", "us", true,
                    "Fully remote role. Candidates must be located in the United States only.");
            detail.with("location").remove("city");
            return detail;
        });

        var raw = source(http, List.of(COMPANY)).fetchCompany(COMPANY).getFirst();
        var decision = new LocationEligibilityService(TestProperties.create()).evaluate(raw);

        assertThat(raw.locationData().workplaceType()).isEqualTo("Remote");
        assertThat(raw.locationData().remoteRegions()).isEmpty();
        assertThat(decision.locationEligibility()).isEqualTo(LocationEligibility.REJECTED_LOCATION);
    }

    @Test
    void legacySectionArrayAndEuropeRemoteContentRemainDeterministic() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            if (!url.contains("/postings/post-")) return page(COMPANY, 0, 1, 1, 0);
            ObjectNode detail = detail(COMPANY, "post-0", "de", true,
                    "placeholder");
            detail.with("location").remove(List.of("city", "country"));
            ArrayNode sections = mapper.createArrayNode();
            sections.addObject().put("identifier", "jobDescription")
                    .put("text", "Fully remote across Europe. Java graduate role.");
            sections.addObject().put("name", "qualifications")
                    .put("text", "No commercial experience required.");
            detail.with("jobAd").set("sections", sections);
            return detail;
        });

        var raw = source(http, List.of(COMPANY)).fetchCompany(COMPANY).getFirst();
        var decision = new LocationEligibilityService(TestProperties.create()).evaluate(raw);

        assertThat(raw.description()).contains("remote across Europe", "No commercial experience");
        assertThat(decision.locationEligibility())
                .isEqualTo(LocationEligibility.REMOTE_ROMANIA_ELIGIBLE);
    }

    @Test
    void malformedListAndConflictingPartitionDuplicatesFailClosed() {
        ExternalHttpClient malformed = mock(ExternalHttpClient.class);
        when(malformed.getJson(anyString())).thenReturn(mapper.createArrayNode());
        assertParseFailure(() -> source(malformed, List.of(COMPANY)).fetchCompany(COMPANY));

        ExternalHttpClient conflict = mock(ExternalHttpClient.class);
        when(conflict.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            ObjectNode page = page(COMPANY, 0, 1, 1, 0);
            if (url.contains("q=remote")) {
                ((ObjectNode) page.withArray("content").get(0)).put("name", "Conflicting title");
            }
            return page;
        });
        assertParseFailure(() -> source(conflict, List.of(COMPANY)).fetchCompany(COMPANY));
    }

    @Test
    void aggregateLimitHasAClosedHealthClassification() {
        var failure = new TenantFailureClassifier().classify("smartrecruiters", COMPANY,
                new SmartRecruitersLimitException(
                        SmartRecruitersLimitException.Limit.UNIQUE_POSTINGS, 500));

        assertThat(failure.category()).isEqualTo(TenantFailureCategory.RESPONSE_TOO_LARGE);
        assertThat(failure.errorMessage())
                .isEqualTo("SmartRecruiters unique-posting cap of 500 was exceeded for "
                        + "smartrecruiters tenant SyntheticCo")
                .doesNotContain("http", "post-", "description");
    }

    @Test
    void transportFailuresKeepTheExistingClosedHealthTaxonomy() {
        MapCase[] cases = {
                new MapCase(new ExternalHttpException(
                        ExternalHttpException.Category.HTTP_STATUS, 404),
                        TenantFailureCategory.INVALID_TENANT),
                new MapCase(new ExternalHttpException(
                        ExternalHttpException.Category.HTTP_STATUS, 403),
                        TenantFailureCategory.AUTHORIZATION_ERROR),
                new MapCase(new ExternalHttpException(
                        ExternalHttpException.Category.HTTP_STATUS, 429),
                        TenantFailureCategory.RATE_LIMITED),
                new MapCase(new ExternalHttpException(
                        ExternalHttpException.Category.HTTP_STATUS, 500),
                        TenantFailureCategory.SERVER_ERROR),
                new MapCase(new ExternalHttpException(
                        ExternalHttpException.Category.TIMEOUT, null),
                        TenantFailureCategory.TIMEOUT),
                new MapCase(new ExternalHttpException(
                        ExternalHttpException.Category.RESPONSE_TOO_LARGE, null)
                        .limitBytes(10_485_760), TenantFailureCategory.RESPONSE_TOO_LARGE)
        };
        TenantFailureClassifier classifier = new TenantFailureClassifier();
        for (MapCase testCase : cases) {
            assertThat(classifier.classify("smartrecruiters", COMPANY, testCase.failure())
                    .category()).isEqualTo(testCase.expected());
        }
    }

    private SmartRecruitersJobSource source(ExternalHttpClient http, List<String> companies) {
        JobPilotProperties base = TestProperties.create();
        JobPilotProperties.Sources sources = new JobPilotProperties.Sources(
                List.of(), List.of(), List.of(), List.of(), companies);
        JobPilotProperties properties = new JobPilotProperties(base.telegram(), sources,
                base.eligibility(), base.candidate(), base.http(), base.manualUrl(), base.llm(),
                base.scheduling(), base.searchTerms(), base.locations());
        return new SmartRecruitersJobSource(http, mapper, new UrlCanonicalizer(), properties);
    }

    private ObjectNode page(String company, int offset, int total, int count, int firstId) {
        ObjectNode page = mapper.createObjectNode();
        page.put("limit", 100).put("offset", offset).put("totalFound", total);
        ArrayNode content = page.putArray("content");
        for (int index = 0; index < count; index++) {
            String id = "post-" + (firstId + index);
            ObjectNode item = content.addObject();
            item.put("id", id).put("uuid", "uuid-" + id).put("name", "Java Role " + id);
            item.putObject("company").put("identifier", company).put("name", "Synthetic Company");
            item.putObject("location").put("city", "Bucharest").put("country", "ro")
                    .put("remote", false);
        }
        return page;
    }

    private ObjectNode detail(String company, String id, String country, boolean remote,
                              String description) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("id", id).put("uuid", "uuid-" + id).put("name", "Java Role " + id)
                .put("releasedDate", "2026-07-30T09:15:00Z")
                .put("postingUrl", "https://careers.example.test/" + company + "/" + id);
        detail.putObject("company").put("identifier", company).put("name", "Synthetic Company");
        detail.putObject("location").put("city", "Bucharest").put("country", country)
                .put("remote", remote);
        detail.putObject("experienceLevel").put("id", "entry").put("label", "Entry level");
        detail.putObject("jobAd").putObject("sections").putObject("jobDescription")
                .put("text", description);
        return detail;
    }

    private int offset(String url) {
        int start = url.indexOf("offset=");
        if (start < 0) return 0;
        start += "offset=".length();
        int end = url.indexOf('&', start);
        return Integer.parseInt(end < 0 ? url.substring(start) : url.substring(start, end));
    }

    private JsonNode fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/fixtures/smartrecruiters/" + name)) {
            return mapper.readTree(input);
        }
    }

    private void assertParseFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ExternalHttpException.class,
                failure -> assertThat(failure.category())
                        .isEqualTo(ExternalHttpException.Category.MALFORMED_JSON));
    }

    private record MapCase(ExternalHttpException failure, TenantFailureCategory expected) { }
}
