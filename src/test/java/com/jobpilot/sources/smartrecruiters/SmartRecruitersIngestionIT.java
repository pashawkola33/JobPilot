package com.jobpilot.sources.smartrecruiters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.jobs.service.JobIngestionReport;
import com.jobpilot.jobs.service.JobIngestionService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "jobpilot.sources.greenhouse-board-tokens=",
        "jobpilot.sources.lever-company-ids=",
        "jobpilot.sources.ashby-board-names=",
        "jobpilot.sources.recruitee-company-ids=",
        "jobpilot.sources.smartrecruiters-company-identifiers=HealthyOne,HealthyTwo,BrokenOne",
        "jobpilot.scheduling.fetch-cron=0 0 0 1 1 *",
        "jobpilot.scheduling.digest-cron=0 0 0 1 1 *",
        "jobpilot.telegram.commands-enabled=false"
})
class SmartRecruitersIngestionIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JobIngestionService ingestion;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @MockBean private ExternalHttpClient http;

    private final Map<String, AtomicInteger> detailRequests = new ConcurrentHashMap<>();

    @BeforeEach
    void prepare() {
        reset(http);
        detailRequests.clear();
        jdbc.update("delete from source_tenant_fetch_logs");
        jdbc.update("delete from source_tenant_health");
        jdbc.update("delete from source_fetch_logs");
        jdbc.update("delete from job_requirements");
        jdbc.update("delete from job_scores");
        jdbc.update("delete from jobs");
        when(http.getJson(anyString())).thenAnswer(invocation -> response(invocation.getArgument(0)));
    }

    @Test
    void persistsTenantScopedIdentitiesOnceAndIsolatesAFailedTenant() {
        JobIngestionReport report = ingestion.fetchAllSources();

        assertThat(report.totalVacanciesFetched()).isEqualTo(2);
        assertThat(report.totalUniqueVacanciesBeforeEligibilityFiltering()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from jobs where source = 'smartrecruiters'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList(
                "select provider_tenant from jobs where source = 'smartrecruiters' "
                        + "order by provider_tenant", String.class))
                .containsExactly("HealthyOne", "HealthyTwo");
        assertThat(jdbc.queryForObject(
                "select count(distinct external_id) from jobs where source = 'smartrecruiters'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from source_tenant_fetch_logs where provider = 'smartrecruiters'",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select count(*) from source_tenant_fetch_logs where provider = 'smartrecruiters' "
                        + "and attempt_status = 'SUCCESS'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select count(*) from source_tenant_fetch_logs where provider = 'smartrecruiters' "
                        + "and tenant = 'BrokenOne' and attempt_status = 'FAILURE' "
                        + "and failure_category = 'SERVER_ERROR' and fetched_count = 0",
                Integer.class)).isEqualTo(1);
        assertThat(detailRequests).containsOnlyKeys("HealthyOne", "HealthyTwo", "BrokenOne");
        assertThat(detailRequests.values()).allSatisfy(count -> assertThat(count).hasValue(1));
        assertThat(jdbc.queryForObject(
                "select count(*) from jobs j join job_scores s on s.job_id = j.id "
                        + "where j.screening_disposition = 'REJECT'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class))
                .isEqualTo(12);
    }

    private Object response(String url) {
        String tenant = url.substring(url.indexOf("/companies/") + "/companies/".length());
        tenant = tenant.substring(0, tenant.indexOf('/'));
        boolean detail = url.matches(".*/postings/[^/?]+$");
        if (!detail) return list(tenant);
        detailRequests.computeIfAbsent(tenant, ignored -> new AtomicInteger()).incrementAndGet();
        if (tenant.equals("BrokenOne")) {
            throw new ExternalHttpException(ExternalHttpException.Category.HTTP_STATUS, 500);
        }
        return detail(tenant);
    }

    private ObjectNode list(String tenant) {
        ObjectNode root = mapper.createObjectNode();
        root.put("limit", 100).put("offset", 0).put("totalFound", 1);
        ObjectNode item = root.putArray("content").addObject();
        item.put("id", "shared-posting").put("uuid", "shared-uuid")
                .put("name", "Junior Java Backend Engineer");
        item.putObject("company").put("identifier", tenant).put("name", tenant + " Ltd");
        item.putObject("location").put("city", "Bucharest").put("country", "ro")
                .put("remote", false);
        return root;
    }

    private ObjectNode detail(String tenant) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", "shared-posting").put("uuid", "shared-uuid")
                .put("name", "Junior Java Backend Engineer")
                .put("releasedDate", "2026-07-30T09:15:00Z")
                .put("postingUrl", "https://jobs.example.test/" + tenant + "/shared-posting");
        root.putObject("company").put("identifier", tenant).put("name", tenant + " Ltd");
        root.putObject("location").put("city", "Bucharest").put("country", "ro")
                .put("remote", false);
        root.putObject("experienceLevel").put("id", "entry").put("label", "Entry level");
        root.putObject("typeOfEmployment").put("id", "full-time").put("label", "Full-time");
        root.putObject("jobAd").putObject("sections").putObject("jobDescription")
                .put("text", "Junior Java and Spring Boot role building REST APIs with PostgreSQL, "
                        + "JUnit, mentorship, and graduate training in Bucharest.");
        return root;
    }
}
