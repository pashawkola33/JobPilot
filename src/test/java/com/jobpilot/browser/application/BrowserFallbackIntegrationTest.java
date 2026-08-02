package com.jobpilot.browser.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.applications.repository.ApplicationRepository;
import com.jobpilot.browser.api.BrowserExtractionResponse;
import com.jobpilot.browser.api.BrowserExtractionStatus;
import com.jobpilot.jobs.repository.JobRepository;
import com.jobpilot.llm.repository.JobAnalysisRepository;
import com.jobpilot.manualurl.application.ManualJobUrlService;
import com.jobpilot.manualurl.domain.ManualJobStatus;
import com.jobpilot.manualurl.fetch.ManualAtsResolver;
import com.jobpilot.manualurl.fetch.ManualFetchedResource;
import com.jobpilot.manualurl.fetch.SafeManualPageFetcher;
import com.jobpilot.manualurl.parse.DeterministicManualJobParser;
import com.jobpilot.manualurl.parse.ManualParseResult;
import com.jobpilot.manualurl.parse.ManualParseStatus;
import com.jobpilot.resume.repository.CoverNoteRepository;
import com.jobpilot.resume.repository.ResumeVersionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Full synthetic path: a public manual URL, a safe Java attempt that reaches a
 * JS-required page, the worker returning bounded data, and the SAME Java
 * normalization/dedup/scoring/persistence pipeline. No live worker is contacted;
 * the client is mocked. No LLM, Telegram, document, or application side effect
 * occurs automatically.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:browser-fallback;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "jobpilot.scraper-worker.enabled=true",
        "jobpilot.scraper-worker.base-url=http://scraper-worker:3000",
        "jobpilot.scraper-worker.shared-secret=0123456789abcdef0123456789abcdef",
})
class BrowserFallbackIntegrationTest {
    private static final String URL = "https://93.184.216.34/vacancies/browser-e2e";

    @Autowired private ManualJobUrlService manual;
    @Autowired private JobRepository jobs;
    @Autowired private ApplicationRepository applications;
    @Autowired private JobAnalysisRepository analyses;
    @Autowired private ResumeVersionRepository resumes;
    @Autowired private CoverNoteRepository coverNotes;

    @MockBean private ManualAtsResolver atsResolver;
    @MockBean private SafeManualPageFetcher pageFetcher;
    @MockBean private DeterministicManualJobParser parser;
    @MockBean private BrowserExtractionClient browserClient;

    @Test
    void jsRequiredPageIsRenderedNormalizedPersistedAndDeduplicatedWithNoSideEffects() {
        var fetched = new ManualFetchedResource(java.net.URI.create(URL), java.net.URI.create(URL),
                "text/html", "<html><body>JavaScript required</body></html>");
        when(atsResolver.fetch(any())).thenReturn(Optional.empty());
        when(pageFetcher.fetch(any())).thenReturn(fetched);
        when(parser.parse(any())).thenReturn(ManualParseResult.failure(ManualParseStatus.UNSUPPORTED_SOURCE));
        when(browserClient.extract(any())).thenReturn(new BrowserExtractionResponse(
                BrowserExtractionStatus.EXTRACTED, "https://93.184.216.34/vacancies/browser-e2e", "BROWSER",
                new BrowserExtractionResponse.Job("Java Backend Intern", "Synthetic Company",
                        "Bucharest, Romania",
                        "Java Spring Boot internship building REST APIs with SQL, testing, and mentorship "
                                + "in a collaborative backend engineering team.",
                        "INTERN", "2026-07-19", "https://93.184.216.34/vacancies/browser-e2e"),
                new BrowserExtractionResponse.Evidence("JSON_LD", "JSON_LD", "JSON_LD")));

        var created = manual.submit(URL);
        var duplicate = manual.submit(URL);

        assertThat(created.status()).isEqualTo(ManualJobStatus.CREATED);
        assertThat(duplicate.status()).isEqualTo(ManualJobStatus.ALREADY_EXISTS);
        assertThat(duplicate.jobId()).isEqualTo(created.jobId());
        assertThat(jobs.count()).isEqualTo(1);

        var job = jobs.findById(created.jobId()).orElseThrow();
        assertThat(job.getTitle()).isEqualTo("Java Backend Intern");
        assertThat(job.getCompany()).isEqualTo("Synthetic Company");
        assertThat(job.getSource()).isEqualTo("browser");
        // The canonical URL is derived from the operator-submitted URL by the Java normalizer.
        assertThat(job.getCanonicalUrl()).startsWith("https://93.184.216.34/");

        // Each submission renders; deduplication happens in the existing persistence
        // layer (one job row, ALREADY_EXISTS), not by caching at the manual layer.
        verify(browserClient, times(2)).extract(any());

        // No automatic downstream side effects.
        assertThat(applications.count()).isZero();
        assertThat(analyses.countByStatus(com.jobpilot.llm.domain.JobAnalysisStatus.SUCCEEDED)).isZero();
        assertThat(resumes.count()).isZero();
        assertThat(coverNotes.count()).isZero();
    }
}
