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
            result.add(parseOne(company, item));
        }
        return result;
    }

    public RawJob parseOne(String company, JsonNode item) {
        String description = plainText(item.path("content").asText(""));
        return new RawJob(getSourceName(), item.path("id").asText(),
                item.path("absolute_url").asText(), item.path("title").asText(),
                company, item.path("location").path("name").asText(""), description,
                null, parseInstant(item.path("updated_at").asText()), null, item.toString());
    }

    private Instant parseInstant(String value) {
        try {
            return value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String plainText(String html) {
        // Greenhouse escapes the HTML itself (&lt;p&gt;), so unescape before stripping tags;
        // the second unescape resolves entities that were inside the HTML text (&amp;amp;).
        String withoutTags = HtmlUtils.htmlUnescape(html).replaceAll("<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(withoutTags).replaceAll("\\s+", " ").trim();
    }
}
