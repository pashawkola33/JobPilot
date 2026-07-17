package com.jobpilot.sources.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.sources.JobSource;
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

    public GreenhouseJobSource(ExternalHttpClient http, JobPilotProperties properties) {
        this.http = http;
        this.boardTokens = properties.sources().greenhouseBoardTokens();
    }

    @Override
    public String getSourceName() {
        return "greenhouse";
    }

    @Override
    public List<RawJob> fetchJobs() {
        List<RawJob> jobs = new ArrayList<>();
        for (String token : boardTokens) {
            JsonNode payload = http.getJson("https://boards-api.greenhouse.io/v1/boards/" + token + "/jobs?content=true");
            jobs.addAll(parse(token, payload));
        }
        return jobs;
    }

    public List<RawJob> parse(String company, JsonNode root) {
        List<RawJob> result = new ArrayList<>();
        for (JsonNode item : root.path("jobs")) {
            String description = plainText(item.path("content").asText(""));
            result.add(new RawJob(getSourceName(), item.path("id").asText(),
                    item.path("absolute_url").asText(), item.path("title").asText(),
                    company, item.path("location").path("name").asText(""), description,
                    null, parseInstant(item.path("updated_at").asText()), null, item.toString()));
        }
        return result;
    }

    private Instant parseInstant(String value) {
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String plainText(String html) {
        return HtmlUtils.htmlUnescape(html.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").trim();
    }
}
