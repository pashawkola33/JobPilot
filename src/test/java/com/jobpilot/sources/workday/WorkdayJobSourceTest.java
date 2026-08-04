package com.jobpilot.sources.workday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.sources.health.SourceTenantHealthRecorder;
import com.jobpilot.sources.health.TenantAttemptStatus;
import com.jobpilot.sources.health.TenantFailureCategory;
import com.jobpilot.sources.health.TenantFailureClassifier;
import com.jobpilot.sources.health.TenantFetchMonitor;
import com.jobpilot.support.TestProperties;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkdayJobSourceTest {
    private static final String DB = "db:wd3:DBWebsite";
    private static final String SEARCH =
            "https://db.wd3.myworkdayjobs.com/wday/cxs/db/DBWebsite/jobs";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requests = new ArrayList<>();

    private JsonNode fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/workday/" + name)) {
            return mapper.readTree(in);
        }
    }

    private WorkdayJobSource source(ExternalHttpClient http, List<String> sites) {
        JobPilotProperties properties = TestProperties.create();
        JobPilotProperties withWorkday = new JobPilotProperties(
                properties.telegram(),
                new JobPilotProperties.Sources(List.of(), List.of(), List.of(), List.of(),
                        List.of(), sites),
                properties.eligibility(), properties.candidate(), properties.http(),
                properties.manualUrl(), properties.llm(), properties.scheduling(),
                properties.searchTerms(), properties.locations());
        return new WorkdayJobSource(http, new UrlCanonicalizer(), new WorkdayFacetResolver(),
                withWorkday);
    }

    /** Builds a search page with the given number of postings, starting at an index. */
    private ObjectNode page(Integer total, int from, int count) {
        ObjectNode root = mapper.createObjectNode();
        if (total != null) root.put("total", total);
        else root.put("total", 0);
        ArrayNode postings = root.putArray("jobPostings");
        for (int i = from; i < from + count; i++) {
            ObjectNode posting = postings.addObject();
            posting.put("title", "Role " + i);
            posting.put("externalPath", "/job/City/Role-" + i + "_R" + i);
            posting.put("locationsText", "Bucharest");
            posting.putArray("bulletFields").add("R" + i).add("Division: anything");
        }
        root.putArray("facets");
        root.put("userAuthenticated", false);
        return root;
    }

    private ObjectNode detailFor(int index) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode info = root.putObject("jobPostingInfo");
        info.put("id", String.format("%032d", index));
        info.put("title", "Role " + index);
        info.put("jobDescription", "<p>Body " + index + "</p>");
        info.put("location", "Bucharest");
        info.put("startDate", "2026-08-01");
        info.put("timeType", "Full time");
        info.putObject("country").put("descriptor", "Romania")
                .put("id", WorkdayFacetResolver.ROMANIA_COUNTRY_ID);
        info.put("externalUrl",
                "https://db.wd3.myworkdayjobs.com/DBWebsite/job/City/Role-" + index + "_R" + index);
        root.putObject("hiringOrganization").put("name", "Example SRL");
        root.put("userAuthenticated", false);
        return root;
    }

    /** Records every request and serves bootstrap, search pages and details from a map. */
    private ExternalHttpClient stub(JsonNode bootstrap, Map<Integer, JsonNode> pagesByOffset,
                                    java.util.function.Function<String, JsonNode> details) {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.postJson(anyString(), any())).thenAnswer(invocation -> {
            Map<?, ?> body = (Map<?, ?>) invocation.getArgument(1);
            int limit = (int) body.get("limit");
            int offset = (int) body.get("offset");
            requests.add("POST " + invocation.getArgument(0) + " limit=" + limit
                    + " offset=" + offset + " facets=" + body.get("appliedFacets"));
            if (limit == 1) return bootstrap;
            JsonNode page = pagesByOffset.get(offset);
            if (page == null) throw new AssertionError("unexpected offset " + offset);
            return page;
        });
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            requests.add("GET " + url);
            return details.apply(url);
        });
        return http;
    }

    // ---------------------------------------------------------------- empty configuration

    @Test
    void emptyConfigurationPerformsZeroHttpCalls() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);

        assertThat(source(http, List.of()).fetchJobs()).isEmpty();

        verifyNoInteractions(http);
    }

    @Test
    void exposesTheProviderName() {
        assertThat(source(mock(ExternalHttpClient.class), List.of()).getSourceName())
                .isEqualTo("workday");
    }

    // ---------------------------------------------------------------- facet application

    @Test
    void appliesTheDiscoveredCountryFacetServerSide() throws Exception {
        ExternalHttpClient http = stub(fixture("db-search-bootstrap.json"),
                Map.of(0, page(1, 0, 1)), url -> detailFor(0));

        source(http, List.of(DB)).fetchJobs();

        assertThat(requests.get(0)).contains("limit=1", "offset=0", "facets={}");
        assertThat(requests.get(1)).contains("limit=20", "offset=0")
                .contains("Country=[" + WorkdayFacetResolver.ROMANIA_COUNTRY_ID + "]");
    }

    @Test
    void fallsBackToUnfilteredPagingWhenNoRomaniaFacetExists() throws Exception {
        ExternalHttpClient http = stub(fixture("search-no-romania-facet.json"),
                Map.of(0, page(1, 0, 1)), url -> detailFor(0));

        assertThat(source(http, List.of(DB)).fetchJobs()).hasSize(1);

        assertThat(requests.get(1)).contains("facets={}");
    }

    // ---------------------------------------------------------------- pagination

    @Test
    void readsTotalFromOffsetZeroAndPagesUntilTheFinalPartialPage() {
        Map<Integer, JsonNode> pages = new LinkedHashMap<>();
        pages.put(0, page(45, 0, 20));
        pages.put(20, page(null, 20, 20));   // later pages report total 0
        pages.put(40, page(null, 40, 5));
        ExternalHttpClient http = stub(page(45, 0, 0), pages, url -> {
            int index = Integer.parseInt(url.replaceAll(".*_R(\\d+)$", "$1"));
            return detailFor(index);
        });

        List<RawJob> jobs = source(http, List.of(DB)).fetchJobs();

        assertThat(jobs).hasSize(45);
        List<String> offsets = requests.stream().filter(r -> r.startsWith("POST"))
                .map(r -> r.replaceAll(".*offset=(\\d+).*", "$1")).toList();
        assertThat(offsets).containsExactly("0", "0", "20", "40");
        assertThat(requests.stream().filter(r -> r.startsWith("GET")).count()).isEqualTo(45);
    }

    @Test
    void stopsAtTheReportedTotalWithoutRequestingAnExtraPage() {
        Map<Integer, JsonNode> pages = new LinkedHashMap<>();
        pages.put(0, page(40, 0, 20));
        pages.put(20, page(null, 20, 20));
        ExternalHttpClient http = stub(page(40, 0, 0), pages, url -> {
            int index = Integer.parseInt(url.replaceAll(".*_R(\\d+)$", "$1"));
            return detailFor(index);
        });

        assertThat(source(http, List.of(DB)).fetchJobs()).hasSize(40);

        assertThat(requests.stream().filter(r -> r.startsWith("POST")).count()).isEqualTo(3);
    }

    @Test
    void stopsOnAnEmptyPage() {
        ExternalHttpClient http = stub(page(0, 0, 0), Map.of(0, page(0, 0, 0)), url -> null);

        assertThat(source(http, List.of(DB)).fetchJobs()).isEmpty();

        assertThat(requests).hasSize(2);
    }

    /** Full pages that repeat the same postings: pages grow, unique postings do not. */
    @Test
    void failsClosedWhenThePageCapIsExceeded() {
        Map<Integer, JsonNode> pages = new LinkedHashMap<>();
        for (int i = 0; i <= WorkdayJobSource.MAX_LIST_PAGES_PER_SITE; i++) {
            pages.put(i * 20, page(i == 0 ? 100_000 : null, 0, 20));
        }
        ExternalHttpClient http = stub(page(100_000, 0, 0), pages, url -> null);
        WorkdayJobSource source = source(http, List.of(DB));

        assertThatThrownBy(() -> source.fetchSite(WorkdayCareerSite.parse(DB)))
                .isInstanceOf(WorkdayLimitException.class)
                .extracting(e -> ((WorkdayLimitException) e).limit())
                .isEqualTo(WorkdayLimitException.Limit.LIST_PAGES);
    }

    @Test
    void failsClosedWhenTheUniquePostingCapIsExceeded() {
        Map<Integer, JsonNode> pages = new LinkedHashMap<>();
        int perPage = 20;
        int needed = WorkdayJobSource.MAX_UNIQUE_JOBS_PER_SITE / perPage + 1;
        for (int i = 0; i < needed; i++) {
            pages.put(i * perPage, page(i == 0 ? 100_000 : null, i * perPage, perPage));
        }
        ExternalHttpClient http = stub(page(100_000, 0, 0), pages, url -> null);
        WorkdayJobSource source = source(http, List.of(DB));

        assertThatThrownBy(() -> source.fetchSite(WorkdayCareerSite.parse(DB)))
                .isInstanceOf(WorkdayLimitException.class)
                .extracting(e -> ((WorkdayLimitException) e).limit())
                .isEqualTo(WorkdayLimitException.Limit.UNIQUE_POSTINGS);
    }

    @Test
    void rejectsAPageLongerThanTheWorkdayMaximum() {
        ExternalHttpClient http = stub(page(30, 0, 0), Map.of(0, page(30, 0, 21)), url -> null);
        WorkdayJobSource source = source(http, List.of(DB));

        assertThatThrownBy(() -> source.fetchSite(WorkdayCareerSite.parse(DB)))
                .isInstanceOf(ExternalHttpException.class);
    }

    @Test
    void neverRequestsTheSameOffsetTwice() {
        Map<Integer, JsonNode> pages = new LinkedHashMap<>();
        pages.put(0, page(45, 0, 20));
        pages.put(20, page(null, 20, 20));
        pages.put(40, page(null, 40, 5));
        ExternalHttpClient http = stub(page(45, 0, 0), pages, url -> {
            int index = Integer.parseInt(url.replaceAll(".*_R(\\d+)$", "$1"));
            return detailFor(index);
        });

        source(http, List.of(DB)).fetchJobs();

        List<String> searchOffsets = requests.stream()
                .filter(r -> r.startsWith("POST") && r.contains("limit=20"))
                .map(r -> r.replaceAll(".*offset=(\\d+).*", "$1")).toList();
        assertThat(searchOffsets).doesNotHaveDuplicates();
    }

    // ---------------------------------------------------------------- mapping

    @Test
    void keepsAPostingWhosePrimaryLocationIsOutsideRomania() throws Exception {
        ObjectNode page = page(1, 0, 1);
        ((ObjectNode) page.get("jobPostings").get(0)).put("locationsText", "2 Locations");
        ExternalHttpClient http = stub(fixture("db-search-bootstrap.json"), Map.of(0, page),
                url -> {
                    try {
                        return fixture("detail-multi-location.json");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        RawJob job = source(http, List.of(DB)).fetchJobs().getFirst();

        assertThat(job.location()).isEqualTo("POL-Gdynia-3T Office Park, Tower C");
        assertThat(job.locationData().structuredLocations())
                .contains("Bucharest, Romania", "Poland", "POL-Gdynia-3T Office Park, Tower C");
        // "2 Locations" is a placeholder, not a place.
        assertThat(job.locationData().structuredLocations()).doesNotContain("2 Locations");
    }

    @Test
    void toleratesAnAbsentLocationsTextInTheSummary() throws Exception {
        ObjectNode page = page(1, 0, 1);
        ((ObjectNode) page.get("jobPostings").get(0)).remove("locationsText");
        ExternalHttpClient http = stub(fixture("db-search-bootstrap.json"), Map.of(0, page),
                url -> {
                    try {
                        return fixture("detail-bucharest.json");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        RawJob job = source(http, List.of(DB)).fetchJobs().getFirst();

        assertThat(job.externalId()).isEqualTo("9999aaaa8888bbbb7777cccc6666dddd");
        assertThat(job.locationData().structuredLocations())
                .containsExactly("Bucharest, Example Blvd", "Romania");
    }

    @Test
    void takesIdentityTitleAndDateFromDetailRatherThanTheSummarySlug() throws Exception {
        ObjectNode page = page(1, 0, 1);
        ObjectNode posting = (ObjectNode) page.get("jobPostings").get(0);
        posting.put("title", "Stale Slug Title");
        posting.put("externalPath", "/job/Old-City/Stale-Slug-Title_R0000000");
        ExternalHttpClient http = stub(fixture("db-search-bootstrap.json"), Map.of(0, page),
                url -> {
                    try {
                        return fixture("detail-bucharest.json");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        RawJob job = source(http, List.of(DB)).fetchJobs().getFirst();

        assertThat(job.externalId()).isEqualTo("9999aaaa8888bbbb7777cccc6666dddd");
        assertThat(job.title()).isEqualTo("Junior Java Developer");
        assertThat(job.publishedAt()).isEqualTo(java.time.Instant.parse("2026-08-04T00:00:00Z"));
        assertThat(job.providerTenant()).isEqualTo("db/DBWebsite");
        assertThat(job.source()).isEqualTo("workday");
    }

    @Test
    void ignoresTenantConfiguredBulletFields() throws Exception {
        ObjectNode page = page(1, 0, 1);
        ObjectNode posting = (ObjectNode) page.get("jobPostings").get(0);
        posting.putArray("bulletFields").add("Maubeuge").add("K - Supply Chain");
        ExternalHttpClient http = stub(fixture("db-search-bootstrap.json"), Map.of(0, page),
                url -> {
                    try {
                        return fixture("detail-bucharest.json");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        RawJob job = source(http, List.of(DB)).fetchJobs().getFirst();

        assertThat(job.externalId()).isEqualTo("9999aaaa8888bbbb7777cccc6666dddd");
        assertThat(job.locationData().structuredLocations()).doesNotContain("K - Supply Chain");
        assertThat(job.location()).doesNotContain("Maubeuge");
    }

    @Test
    void rebuildsTheCanonicalUrlWhenTheProviderPointsOffSite() throws Exception {
        ExternalHttpClient http = stub(fixture("db-search-bootstrap.json"),
                Map.of(0, page(1, 0, 1)), url -> {
                    try {
                        return fixture("detail-offsite-url.json");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        RawJob job = source(http, List.of(DB)).fetchJobs().getFirst();

        assertThat(job.url()).doesNotContain("aggregator.example.test")
                .startsWith("https://db.wd3.myworkdayjobs.com/DBWebsite/job/");
    }

    @Test
    void stripsProviderMarkupFromTheDescription() throws Exception {
        ExternalHttpClient http = stub(fixture("db-search-bootstrap.json"),
                Map.of(0, page(1, 0, 1)), url -> {
                    try {
                        return fixture("detail-multi-location.json");
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });

        RawJob job = source(http, List.of(DB)).fetchJobs().getFirst();

        assertThat(job.description()).doesNotContain("<").doesNotContain(">")
                .contains("Java").contains("&");
    }

    // ---------------------------------------------------------------- health integration

    private TenantFetchMonitor monitor(SourceTenantHealthRecorder recorder) {
        return new TenantFetchMonitor(new TenantFailureClassifier(), recorder,
                java.time.Clock.systemUTC());
    }

    @Test
    void recordsASuccessfulTenantAttemptUnderTheCareerSiteKey() throws Exception {
        SourceTenantHealthRecorder recorder = mock(SourceTenantHealthRecorder.class);
        ExternalHttpClient http = stub(fixture("db-search-bootstrap.json"),
                Map.of(0, page(1, 0, 1)), url -> detailFor(0));
        WorkdayJobSource source = new WorkdayJobSource(http, new UrlCanonicalizer(),
                new WorkdayFacetResolver(), propertiesWith(List.of(DB)), monitor(recorder));

        assertThat(source.fetchJobs()).hasSize(1);

        org.mockito.Mockito.verify(recorder).record(any(), org.mockito.ArgumentMatchers.eq("workday"),
                org.mockito.ArgumentMatchers.eq("db/DBWebsite"),
                org.mockito.ArgumentMatchers.eq(TenantAttemptStatus.SUCCESS),
                any(), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    @Test
    void categorizesACapBreachAsAFailedTenantAttempt() {
        SourceTenantHealthRecorder recorder = mock(SourceTenantHealthRecorder.class);
        Map<Integer, JsonNode> pages = new LinkedHashMap<>();
        for (int i = 0; i <= WorkdayJobSource.MAX_LIST_PAGES_PER_SITE; i++) {
            pages.put(i * 20, page(i == 0 ? 100_000 : null, i * 20, 20));
        }
        ExternalHttpClient http = stub(page(100_000, 0, 0), pages, url -> null);
        WorkdayJobSource source = new WorkdayJobSource(http, new UrlCanonicalizer(),
                new WorkdayFacetResolver(), propertiesWith(List.of(DB)), monitor(recorder));

        assertThat(source.fetchJobs()).isEmpty();

        org.mockito.ArgumentCaptor<com.jobpilot.sources.health.TenantFailure> failure =
                org.mockito.ArgumentCaptor.forClass(com.jobpilot.sources.health.TenantFailure.class);
        org.mockito.Mockito.verify(recorder).record(any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(TenantAttemptStatus.FAILURE), failure.capture(),
                org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.anyLong(),
                any(), any());
        assertThat(failure.getValue().category()).isEqualTo(TenantFailureCategory.RESPONSE_TOO_LARGE);
        assertThat(failure.getValue().errorMessage()).contains("unique-postings cap of 300");
    }

    @Test
    void oneBrokenCareerSiteDoesNotPreventTheNext() throws Exception {
        SourceTenantHealthRecorder recorder = mock(SourceTenantHealthRecorder.class);
        JsonNode bootstrap = fixture("db-search-bootstrap.json");
        ObjectNode good = page(1, 0, 1);
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.postJson(anyString(), any())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0);
            Map<?, ?> body = (Map<?, ?>) invocation.getArgument(1);
            if (url.contains("/nxp/")) throw new IllegalStateException("tenant is broken");
            return (int) body.get("limit") == 1 ? bootstrap : good;
        });
        when(http.getJson(anyString())).thenReturn(detailFor(0));
        WorkdayJobSource source = new WorkdayJobSource(http, new UrlCanonicalizer(),
                new WorkdayFacetResolver(),
                propertiesWith(List.of("nxp:wd3:careers", DB)), monitor(recorder));

        List<RawJob> jobs = source.fetchJobs();

        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().providerTenant()).isEqualTo("db/DBWebsite");
        org.mockito.Mockito.verify(recorder, org.mockito.Mockito.times(2))
                .record(any(), anyString(), anyString(), any(), any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }

    private JobPilotProperties propertiesWith(List<String> sites) {
        JobPilotProperties base = TestProperties.create();
        return new JobPilotProperties(base.telegram(),
                new JobPilotProperties.Sources(List.of(), List.of(), List.of(), List.of(),
                        List.of(), sites),
                base.eligibility(), base.candidate(), base.http(), base.manualUrl(), base.llm(),
                base.scheduling(), base.searchTerms(), base.locations());
    }
}
