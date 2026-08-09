package com.jobpilot.sources.lever;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.sources.health.SourceTenantHealth;
import com.jobpilot.sources.health.SourceTenantHealthRecorder;
import com.jobpilot.sources.health.TenantAttemptStatus;
import com.jobpilot.sources.health.TenantFailure;
import com.jobpilot.sources.health.TenantFailureCategory;
import com.jobpilot.sources.health.TenantFailureClassifier;
import com.jobpilot.sources.health.TenantFetchMonitor;
import com.jobpilot.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class LeverJobSourceTest {
    private static final String COMPANY = "acme";
    private static final String OTHER = "othercorp";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> requests = new ArrayList<>();

    @Test
    void parsesPublicPostingPayload() throws Exception {
        var source = new LeverJobSource(mock(ExternalHttpClient.class), TestProperties.create());
        var json = new ObjectMapper().readTree("""
                [{"id":"abc","text":"Software Engineer Intern","hostedUrl":"https://jobs.example/abc",
                "description":"<p>Java &amp; REST</p>","createdAt":1784200000000,
                "lists":[{"text":"Requirements","content":"<li>No experience required</li>"}],
                "categories":{"location":"Romania","commitment":"Internship","level":"Entry Level"}}]
                """);

        var jobs = source.parse("acme", json);

        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.externalId()).isEqualTo("abc");
            assertThat(job.url()).isEqualTo("https://jobs.example/abc");
            assertThat(job.title()).isEqualTo("Software Engineer Intern");
            assertThat(job.location()).isEqualTo("Romania");
            assertThat(job.locationData().structuredLocations()).containsExactly("Romania");
            assertThat(job.description()).isEqualTo("Java & REST Requirements No experience required");
            assertThat(job.employmentType()).isEqualTo("Internship");
            assertThat(job.careerData().providerSeniority()).isEqualTo("Entry Level");
        });
    }

    @Test
    void aShortFirstPageIsFetchedInASingleRequest() {
        ExternalHttpClient http = pages(skip -> page(skip, 3));

        var jobs = source(http, List.of(COMPANY)).fetchCompany(COMPANY);

        assertThat(jobs).extracting(RawJob::externalId)
                .containsExactly("job-0", "job-1", "job-2");
        assertThat(jobs).allSatisfy(job -> {
            assertThat(job.source()).isEqualTo("lever");
            assertThat(job.providerTenant()).isEqualTo(COMPANY);
        });
        assertThat(requests).containsExactly(url(COMPANY, 0));
    }

    @Test
    void aFullPageFollowedByAnEmptyPageReturnsEveryPosting() {
        ExternalHttpClient http = pages(skip -> skip == 0 ? page(0, 50) : page(0, 0));

        var jobs = source(http, List.of(COMPANY)).fetchCompany(COMPANY);

        assertThat(jobs).hasSize(50);
        assertThat(jobs).extracting(RawJob::externalId).startsWith("job-0").endsWith("job-49");
        assertThat(requests).containsExactly(url(COMPANY, 0), url(COMPANY, 50));
    }

    @Test
    void everyPageIsReturnedInProviderOrderWithAdvancingSkip() {
        ExternalHttpClient http = pages(skip -> switch (skip) {
            case 0 -> page(0, 50);
            case 50 -> page(50, 50);
            default -> page(100, 20);
        });

        var jobs = source(http, List.of(COMPANY)).fetchCompany(COMPANY);

        assertThat(jobs).extracting(RawJob::externalId)
                .containsExactlyElementsOf(ids(0, 120));
        assertThat(requests).containsExactly(url(COMPANY, 0), url(COMPANY, 50), url(COMPANY, 100));
    }

    @Test
    void aShortPageAfterAFullPageStopsWithoutAnotherRequest() {
        ExternalHttpClient http = pages(skip -> skip == 0 ? page(0, 50) : page(50, 10));

        var jobs = source(http, List.of(COMPANY)).fetchCompany(COMPANY);

        assertThat(jobs).extracting(RawJob::externalId).containsExactlyElementsOf(ids(0, 60));
        assertThat(requests).containsExactly(url(COMPANY, 0), url(COMPANY, 50));
    }

    @Test
    void postingsRepeatedAcrossAPageBoundaryAreReturnedOnce() {
        // The second page re-sends the last posting of the first page, as an overlapping
        // board would; the third page ends the tenant.
        ExternalHttpClient http = pages(skip -> switch (skip) {
            case 0 -> page(0, 50);
            case 50 -> page(49, 50);
            default -> page(0, 0);
        });

        var jobs = source(http, List.of(COMPANY)).fetchCompany(COMPANY);

        assertThat(jobs).extracting(RawJob::externalId)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(ids(0, 99));
        assertThat(requests).containsExactly(url(COMPANY, 0), url(COMPANY, 50), url(COMPANY, 100));
    }

    @Test
    void aBoardThatIgnoresSkipFailsInsteadOfPagingForever() {
        ExternalHttpClient http = pages(skip -> page(0, 50));

        assertParseFailure(() -> source(http, List.of(COMPANY)).fetchCompany(COMPANY));

        assertThat(requests).containsExactly(url(COMPANY, 0), url(COMPANY, 50));
        assertThat(new TenantFailureClassifier().classify("lever", COMPANY, thrown(http)).category())
                .isEqualTo(TenantFailureCategory.RESPONSE_PARSE_ERROR);
    }

    @Test
    void aBoardThatNeverEndsStopsAtTheConfiguredPageCap() {
        ExternalHttpClient http = pages(skip -> page(skip, 50));

        ExternalHttpException failure = thrown(http);

        assertThat(failure.category()).isEqualTo(ExternalHttpException.Category.RESPONSE_TOO_LARGE);
        assertThat(requests).hasSize(LeverJobSource.MAX_PAGES_PER_TENANT);
        assertThat(new TenantFailureClassifier().classify("lever", COMPANY, failure).category())
                .isEqualTo(TenantFailureCategory.RESPONSE_TOO_LARGE);
    }

    @Test
    void aLaterPageFailureDiscardsTheTenantAndLeavesOtherTenantsFetchable() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0, String.class);
            requests.add(url);
            if (!url.contains(COMPANY)) return page(0, 2);
            if (skip(url) == 0) return page(0, 50);
            throw new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, 500);
        });

        var jobs = source(http, List.of(COMPANY, OTHER)).fetchJobs();

        // Not one posting of the failed tenant's first page escapes, and the later tenant ran.
        assertThat(jobs).extracting(RawJob::providerTenant).containsOnly(OTHER);
        assertThat(jobs).hasSize(2);
        assertThat(requests).containsExactly(url(COMPANY, 0), url(COMPANY, 50), url(OTHER, 0));
    }

    @Test
    void oneMonitoredAttemptPerTenantRecordsThePostingsOfEveryPage() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0, String.class);
            requests.add(url);
            if (!url.contains(COMPANY)) {
                throw new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, 500);
            }
            return skip(url) == 0 ? page(0, 50) : page(50, 10);
        });
        RecordingRecorder recorder = new RecordingRecorder();
        TenantFetchMonitor monitor = new TenantFetchMonitor(new TenantFailureClassifier(), recorder,
                Clock.fixed(Instant.parse("2026-08-06T12:00:00Z"), ZoneOffset.UTC));

        var jobs = source(http, List.of(COMPANY, OTHER), monitor).fetchJobs();

        assertThat(jobs).hasSize(60);
        // Two paginated requests for the healthy tenant, but exactly one attempt per tenant.
        assertThat(recorder.calls).hasSize(2);
        assertThat(recorder.byTenant(COMPANY).status).isEqualTo(TenantAttemptStatus.SUCCESS);
        assertThat(recorder.byTenant(COMPANY).fetchedCount).isEqualTo(60);
        assertThat(recorder.byTenant(OTHER).status).isEqualTo(TenantAttemptStatus.FAILURE);
        assertThat(recorder.byTenant(OTHER).failure.category())
                .isEqualTo(TenantFailureCategory.SERVER_ERROR);
        assertThat(recorder.byTenant(OTHER).fetchedCount).isZero();
    }

    @Test
    void aPageThatIsNotAJsonArrayIsRejected() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        ObjectNode notAnArray = mapper.createObjectNode();
        notAnArray.putArray("postings").add(mapper.createObjectNode().put("id", "job-0"));
        when(http.getJson(anyString())).thenReturn(notAnArray);

        assertParseFailure(() -> source(http, List.of(COMPANY)).fetchCompany(COMPANY));
        assertThatThrownBy(() -> source(http, List.of(COMPANY)).fetchCompany(COMPANY))
                .isInstanceOfSatisfying(ExternalHttpException.class, failure -> {
                    assertThat(failure.parseDetail())
                            .isEqualTo("page1: expected ARRAY but was OBJECT");
                    // Rule and JSON type only: no value, URL, tenant, or fragment.
                    assertThat(failure.parseDetail()).doesNotContain("http", COMPANY, "job-0");
                });
    }

    @Test
    void noConfiguredTenantMakesNoRequest() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);

        assertThat(source(http, List.of()).fetchJobs()).isEmpty();

        verifyNoInteractions(http);
    }

    private ExternalHttpException thrown(ExternalHttpClient http) {
        requests.clear();
        try {
            source(http, List.of(COMPANY)).fetchCompany(COMPANY);
        } catch (ExternalHttpException failure) {
            return failure;
        }
        throw new AssertionError("The tenant fetch was expected to fail");
    }

    private void assertParseFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ExternalHttpException.class,
                failure -> assertThat(failure.category())
                        .isEqualTo(ExternalHttpException.Category.MALFORMED_JSON));
    }

    /** Answers every request from the requested {@code skip} and records the URL. */
    private ExternalHttpClient pages(IntFunction<ArrayNode> bySkip) {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0, String.class);
            requests.add(url);
            return bySkip.apply(skip(url));
        });
        return http;
    }

    private LeverJobSource source(ExternalHttpClient http, List<String> companies) {
        return source(http, companies, TenantFetchMonitor.disabled());
    }

    private LeverJobSource source(ExternalHttpClient http, List<String> companies,
                                  TenantFetchMonitor monitor) {
        JobPilotProperties base = TestProperties.create();
        JobPilotProperties.Sources sources = new JobPilotProperties.Sources(
                List.of(), companies, List.of(), List.of(), List.of());
        JobPilotProperties properties = new JobPilotProperties(base.telegram(), sources,
                base.eligibility(), base.candidate(), base.http(), base.manualUrl(), base.llm(),
                base.scheduling(), base.searchTerms(), base.locations());
        return new LeverJobSource(http, properties, monitor);
    }

    private ArrayNode page(int firstId, int count) {
        ArrayNode page = mapper.createArrayNode();
        for (int index = 0; index < count; index++) {
            String id = "job-" + (firstId + index);
            ObjectNode item = page.addObject();
            item.put("id", id).put("text", "Java Engineer " + id)
                    .put("hostedUrl", "https://jobs.example.test/" + id)
                    .put("descriptionPlain", "Java role " + id)
                    .put("createdAt", 1784200000000L);
            item.putObject("categories").put("location", "Bucharest")
                    .put("commitment", "Internship").put("level", "Entry Level");
        }
        return page;
    }

    private List<String> ids(int firstId, int count) {
        List<String> ids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) ids.add("job-" + (firstId + index));
        return ids;
    }

    private String url(String company, int skip) {
        return "https://api.lever.co/v0/postings/" + company + "?mode=json&skip=" + skip
                + "&limit=50";
    }

    private int skip(String url) {
        int start = url.indexOf("skip=") + "skip=".length();
        int end = url.indexOf('&', start);
        return Integer.parseInt(end < 0 ? url.substring(start) : url.substring(start, end));
    }

    private static final class Call {
        private String tenant;
        private TenantAttemptStatus status;
        private TenantFailure failure;
        private int fetchedCount;
    }

    private static final class RecordingRecorder extends SourceTenantHealthRecorder {
        private final List<Call> calls = new ArrayList<>();

        private RecordingRecorder() {
            super(null, null);
        }

        private Call byTenant(String tenant) {
            return calls.stream().filter(call -> call.tenant.equals(tenant)).findFirst()
                    .orElseThrow(() -> new AssertionError("No attempt recorded for " + tenant));
        }

        @Override
        public SourceTenantHealth record(UUID runId, String provider, String tenant,
                                         TenantAttemptStatus status, TenantFailure failure,
                                         int fetchedCount, long durationMs,
                                         Instant startedAt, Instant finishedAt) {
            Call call = new Call();
            call.tenant = tenant;
            call.status = status;
            call.failure = failure;
            call.fetchedCount = fetchedCount;
            calls.add(call);
            return null;
        }
    }
}
