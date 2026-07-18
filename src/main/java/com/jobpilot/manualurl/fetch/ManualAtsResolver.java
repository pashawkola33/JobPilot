package com.jobpilot.manualurl.fetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.sources.greenhouse.GreenhouseJobSource;
import com.jobpilot.sources.lever.LeverJobSource;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ManualAtsResolver {
    private static final List<String> GREENHOUSE_HOSTS = List.of(
            "boards.greenhouse.io", "job-boards.greenhouse.io", "boards.eu.greenhouse.io");
    private static final List<String> LEVER_HOSTS = List.of("jobs.lever.co", "jobs.eu.lever.co");

    private final ExternalHttpClient http;
    private final GreenhouseJobSource greenhouse;
    private final LeverJobSource lever;

    public ManualAtsResolver(ExternalHttpClient http, GreenhouseJobSource greenhouse,
                             LeverJobSource lever) {
        this.http = http;
        this.greenhouse = greenhouse;
        this.lever = lever;
    }

    public Optional<RawJob> fetch(URI submittedUrl) {
        String host = submittedUrl.getHost().toLowerCase(Locale.ROOT);
        List<String> segments = pathSegments(submittedUrl);
        if (GREENHOUSE_HOSTS.contains(host)) {
            return greenhouse(submittedUrl, host, segments);
        }
        if (LEVER_HOSTS.contains(host)) {
            return lever(submittedUrl, host, segments);
        }
        return Optional.empty();
    }

    private Optional<RawJob> greenhouse(URI submittedUrl, String host, List<String> segments) {
        int jobsIndex = segments.indexOf("jobs");
        if (jobsIndex != 1 || segments.size() <= jobsIndex + 1) {
            return Optional.empty();
        }
        String board = segments.getFirst();
        String id = segments.get(jobsIndex + 1);
        if (!safeIdentifier(board) || !id.matches("\\d+")) {
            return Optional.empty();
        }
        String apiHost = host.equals("boards.eu.greenhouse.io")
                ? "boards-api.eu.greenhouse.io" : "boards-api.greenhouse.io";
        JsonNode item = http.getJson("https://" + apiHost + "/v1/boards/" + board
                + "/jobs/" + id + "?content=true");
        return Optional.of(useSubmittedUrlWhenMissing(greenhouse.parseOne(board, item), submittedUrl));
    }

    private Optional<RawJob> lever(URI submittedUrl, String host, List<String> segments) {
        if (segments.size() < 2 || !safeIdentifier(segments.get(0))
                || !safeIdentifier(segments.get(1))) {
            return Optional.empty();
        }
        String company = segments.get(0);
        String id = segments.get(1);
        String apiHost = host.equals("jobs.eu.lever.co") ? "api.eu.lever.co" : "api.lever.co";
        JsonNode item = http.getJson("https://" + apiHost + "/v0/postings/" + company + "/" + id);
        return Optional.of(useSubmittedUrlWhenMissing(lever.parseOne(company, item), submittedUrl));
    }

    private RawJob useSubmittedUrlWhenMissing(RawJob job, URI submittedUrl) {
        String url = job.url() == null || job.url().isBlank() ? submittedUrl.toString() : job.url();
        return new RawJob(job.source(), job.externalId(), url, job.title(), job.company(),
                job.location(), job.description(), job.employmentType(), job.publishedAt(),
                job.deadline(), job.rawPayload());
    }

    private List<String> pathSegments(URI uri) {
        return Arrays.stream(String.valueOf(uri.getPath()).split("/"))
                .filter(segment -> !segment.isBlank()).toList();
    }

    private boolean safeIdentifier(String value) {
        return value.matches("[A-Za-z0-9_-]{1,200}");
    }
}
