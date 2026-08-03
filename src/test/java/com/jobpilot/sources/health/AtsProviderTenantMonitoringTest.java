package com.jobpilot.sources.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.ashby.AshbyJobSource;
import com.jobpilot.sources.greenhouse.GreenhouseJobSource;
import com.jobpilot.sources.lever.LeverJobSource;
import com.jobpilot.sources.recruitee.RecruiteeJobSource;
import com.jobpilot.support.TestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Every ATS adapter reports each tenant attempt through the shared monitor. */
class AtsProviderTenantMonitoringTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HEALTHY = "healthytenant";
    private static final String BROKEN = "brokentenant";

    private final RecordingRecorder recorder = new RecordingRecorder();
    private final TenantFetchMonitor monitor = new TenantFetchMonitor(
            new TenantFailureClassifier(), recorder,
            Clock.fixed(Instant.parse("2026-08-03T12:00:00Z"), ZoneOffset.UTC));

    @AfterEach
    void closeRun() {
        IngestionRunContext.clear();
    }

    static List<org.junit.jupiter.params.provider.Arguments> providers() {
        return List.of(
                org.junit.jupiter.params.provider.Arguments.of("greenhouse",
                        payload("greenhouse"), factory("greenhouse")),
                org.junit.jupiter.params.provider.Arguments.of("lever",
                        payload("lever"), factory("lever")),
                org.junit.jupiter.params.provider.Arguments.of("ashby",
                        payload("ashby"), factory("ashby")),
                org.junit.jupiter.params.provider.Arguments.of("recruitee",
                        payload("recruitee"), factory("recruitee")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void recordsTenantSuccessAndFailureWithoutBreakingIsolation(
            String provider, String payload,
            BiFunction<ExternalHttpClient, TenantFetchMonitor, JobSource> factory) throws Exception {
        JsonNode body = MAPPER.readTree(payload);
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        when(http.getJson(anyString())).thenAnswer(invocation -> {
            String url = invocation.getArgument(0, String.class);
            if (url.contains(BROKEN)) {
                throw new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, 404);
            }
            return body;
        });

        JobSource source = factory.apply(http, monitor);
        List<RawJob> jobs = source.fetchJobs();

        // The healthy tenant still produced vacancies despite the broken tenant failing.
        assertThat(jobs).isNotEmpty();
        assertThat(recorder.calls).hasSize(2);
        assertThat(recorder.calls).extracting(call -> call.provider).containsOnly(provider);

        Call broken = recorder.byTenant(BROKEN);
        assertThat(broken.status).isEqualTo(TenantAttemptStatus.FAILURE);
        assertThat(broken.failure.category()).isEqualTo(TenantFailureCategory.INVALID_TENANT);
        assertThat(broken.failure.httpStatus()).isEqualTo(404);
        assertThat(broken.failure.errorMessage())
                .isEqualTo("HTTP 404 for " + provider + " tenant " + BROKEN
                        + "; the board or company does not exist");
        assertThat(broken.fetchedCount).isZero();

        Call healthy = recorder.byTenant(HEALTHY);
        assertThat(healthy.status).isEqualTo(TenantAttemptStatus.SUCCESS);
        assertThat(healthy.failure.category()).isEqualTo(TenantFailureCategory.NONE);
        assertThat(healthy.fetchedCount).isPositive();
    }

    @Test
    void oneFailingProviderDoesNotStopAnotherProvider() throws Exception {
        ExternalHttpClient failing = mock(ExternalHttpClient.class);
        when(failing.getJson(anyString())).thenThrow(
                new ExternalHttpException(ExternalHttpException.Category.IO, null));
        ExternalHttpClient working = mock(ExternalHttpClient.class);
        when(working.getJson(anyString())).thenReturn(MAPPER.readTree(payload("ashby")));

        List<RawJob> collected = new ArrayList<>();
        collected.addAll(factory("greenhouse").apply(failing, monitor).fetchJobs());
        collected.addAll(factory("ashby").apply(working, monitor).fetchJobs());

        assertThat(collected).isNotEmpty();
        assertThat(recorder.calls).hasSize(4);
        assertThat(recorder.providers()).containsExactly("greenhouse", "ashby");
        // Greenhouse failed on both tenants; Ashby still fetched both of its own.
        assertThat(recorder.byProvider("greenhouse"))
                .allSatisfy(call -> assertThat(call.status).isEqualTo(TenantAttemptStatus.FAILURE));
        assertThat(recorder.byProviderAndTenant("ashby", HEALTHY).status)
                .isEqualTo(TenantAttemptStatus.SUCCESS);
    }

    private static BiFunction<ExternalHttpClient, TenantFetchMonitor, JobSource> factory(
            String provider) {
        return (http, monitor) -> {
            JobPilotProperties properties = tenants(provider);
            return switch (provider) {
                case "greenhouse" -> new GreenhouseJobSource(http, properties, monitor);
                case "lever" -> new LeverJobSource(http, properties, monitor);
                case "ashby" -> new AshbyJobSource(http, properties, monitor);
                default -> new RecruiteeJobSource(http, properties, monitor);
            };
        };
    }

    /** Only the provider under test is configured, so the loop count is exactly two. */
    private static JobPilotProperties tenants(String provider) {
        List<String> configured = List.of(HEALTHY, BROKEN);
        List<String> none = List.of();
        JobPilotProperties base = TestProperties.create();
        JobPilotProperties.Sources sources = new JobPilotProperties.Sources(
                provider.equals("greenhouse") ? configured : none,
                provider.equals("lever") ? configured : none,
                provider.equals("ashby") ? configured : none,
                provider.equals("recruitee") ? configured : none);
        return new JobPilotProperties(base.telegram(), sources, base.eligibility(), base.candidate(),
                base.http(), base.manualUrl(), base.llm(), base.scheduling(), base.searchTerms(),
                base.locations());
    }

    private static String payload(String provider) {
        return switch (provider) {
            case "greenhouse" -> """
                    {"jobs":[{"id":1,"title":"Java Intern","absolute_url":"https://x.example/1",
                    "location":{"name":"Bucharest"},"content":"Java internship","updated_at":"2026-07-16T10:00:00Z"}]}
                    """;
            case "lever" -> """
                    [{"id":"a1","text":"Java Intern","hostedUrl":"https://x.example/a1",
                    "categories":{"location":"Bucharest","commitment":"Internship"},
                    "descriptionPlain":"Java internship","createdAt":1750000000000}]
                    """;
            case "ashby" -> """
                    {"jobs":[{"id":"b2","title":"Java Intern","jobUrl":"https://x.example/b2",
                    "location":"Bucharest","isListed":true,"descriptionPlain":"Java internship"}]}
                    """;
            default -> """
                    {"offers":[{"id":3,"guid":"g3","title":"Java Intern",
                    "careers_url":"https://x.example/o/3","location":"Bucharest",
                    "description":"Java internship","company_name":"Example"}]}
                    """;
        };
    }

    private static final class Call {
        private UUID runId;
        private String provider;
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

        private Call byProviderAndTenant(String provider, String tenant) {
            return calls.stream()
                    .filter(call -> call.provider.equals(provider) && call.tenant.equals(tenant))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "No attempt recorded for " + provider + "/" + tenant));
        }

        private List<Call> byProvider(String provider) {
            return calls.stream().filter(call -> call.provider.equals(provider)).toList();
        }

        private List<String> providers() {
            return calls.stream().map(call -> call.provider).distinct().toList();
        }

        @Override
        public SourceTenantHealth record(UUID runId, String provider, String tenant,
                                         TenantAttemptStatus status, TenantFailure failure,
                                         int fetchedCount, long durationMs,
                                         Instant startedAt, Instant finishedAt) {
            Call call = new Call();
            call.runId = runId;
            call.provider = provider;
            call.tenant = tenant;
            call.status = status;
            call.failure = failure;
            call.fetchedCount = fetchedCount;
            calls.add(call);
            return null;
        }
    }
}
