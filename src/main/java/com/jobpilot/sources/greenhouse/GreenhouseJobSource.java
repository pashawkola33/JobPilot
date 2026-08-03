package com.jobpilot.sources.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawCareerData;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.health.TenantFetchMonitor;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class GreenhouseJobSource implements JobSource {
    private final ExternalHttpClient http;
    private final List<String> boardTokens;
    private final TenantFetchMonitor monitor;

    @Autowired
    public GreenhouseJobSource(ExternalHttpClient http, JobPilotProperties properties,
                               TenantFetchMonitor monitor) {
        this.http = http;
        this.boardTokens = properties.sources().greenhouseBoardTokens();
        this.monitor = monitor;
    }

    public GreenhouseJobSource(ExternalHttpClient http, JobPilotProperties properties) {
        this(http, properties, TenantFetchMonitor.disabled());
    }

    @Override
    public String getSourceName() {
        return "greenhouse";
    }

    @Override
    public List<RawJob> fetchJobs() {
        List<RawJob> jobs = new ArrayList<>();
        // One monitored attempt per tenant: a failure is classified, recorded, and
        // isolated to that tenant so the remaining boards are still fetched.
        for (String token : boardTokens) {
            jobs.addAll(monitor.fetch(getSourceName(), token, () -> parse(token,
                    http.getJson("https://boards-api.greenhouse.io/v1/boards/"
                            + token + "/jobs?content=true"))));
        }
        return jobs;
    }

    public List<RawJob> parse(String company, JsonNode root) {
        List<RawJob> result = new ArrayList<>();
        for (JsonNode item : root.path("jobs")) {
            result.add(parseOne(company, item));
        }
        return result;
    }

    public RawJob parseOne(String company, JsonNode item) {
        String description = plainText(item.path("content").asText(""));
        String location = item.path("location").path("name").asText("");
        return new RawJob(getSourceName(), item.path("id").asText(),
                item.path("absolute_url").asText(), item.path("title").asText(),
                company, location, description,
                null, parseInstant(item.path("updated_at").asText()), null, item.toString(),
                new RawLocationData(item.path("workplace_type").asText(null),
                        List.of(location), List.of(), null, List.of(), null, null), company,
                new RawCareerData(metadataValue(item, "seniority", "job level", "career level"),
                        null, null, null, null));
    }

    private String metadataValue(JsonNode item, String... acceptedNames) {
        for (JsonNode metadata : item.path("metadata")) {
            String name = metadata.path("name").asText("").strip();
            if (java.util.Arrays.stream(acceptedNames).noneMatch(name::equalsIgnoreCase)) continue;
            JsonNode value = metadata.path("value");
            if (value.isArray()) {
                return java.util.stream.StreamSupport.stream(value.spliterator(), false)
                        .map(JsonNode::asText).filter(text -> !text.isBlank())
                        .reduce((left, right) -> left + ", " + right).orElse(null);
            }
            return value.asText(null);
        }
        return null;
    }

    private Instant parseInstant(String value) {
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String plainText(String html) {
        String withoutTags = HtmlUtils.htmlUnescape(html).replaceAll("<[^>]+>", " ");
        return withoutTags.replaceAll("\\s+", " ").trim();
    }
}
