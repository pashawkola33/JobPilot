package com.jobpilot.sources.greenhouse;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawCareerData;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.sources.JobSource;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class GreenhouseJobSource implements JobSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(GreenhouseJobSource.class);
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
            try {
                JsonNode payload = http.getJson("https://boards-api.greenhouse.io/v1/boards/"
                        + token + "/jobs?content=true");
                jobs.addAll(parse(token, payload));
            } catch (RuntimeException failure) {
                LOGGER.warn("Greenhouse tenant {} failed: {}", token, failure.getClass().getSimpleName());
            }
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
        // Greenhouse escapes the HTML itself (&lt;p&gt;), so unescape before stripping tags;
        // the second unescape resolves entities that were inside the HTML text (&amp;amp;).
        String withoutTags = HtmlUtils.htmlUnescape(html).replaceAll("<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(withoutTags).replaceAll("\\s+", " ").trim();
    }
}
