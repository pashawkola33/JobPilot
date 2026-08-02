package com.jobpilot.sources.ashby;

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
public class AshbyJobSource implements JobSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(AshbyJobSource.class);
    private final ExternalHttpClient http;
    private final List<String> boards;

    public AshbyJobSource(ExternalHttpClient http, JobPilotProperties properties) {
        this.http = http;
        this.boards = properties.sources().ashbyBoardNames();
    }

    @Override
    public String getSourceName() {
        return "ashby";
    }

    @Override
    public List<RawJob> fetchJobs() {
        List<RawJob> jobs = new ArrayList<>();
        for (String board : boards) {
            try {
                JsonNode payload = http.getJson(
                        "https://api.ashbyhq.com/posting-api/job-board/" + board);
                jobs.addAll(parse(board, payload));
            } catch (RuntimeException failure) {
                LOGGER.warn("Ashby tenant {} failed: {}", board, failure.getClass().getSimpleName());
            }
        }
        return jobs;
    }

    public List<RawJob> parse(String board, JsonNode root) {
        List<RawJob> result = new ArrayList<>();
        for (JsonNode item : root.path("jobs")) {
            if (!item.path("isListed").asBoolean(true)) continue;
            result.add(parseOne(board, item));
        }
        return result;
    }

    public RawJob parseOne(String board, JsonNode item) {
        String location = item.path("location").asText("");
        List<String> locations = new ArrayList<>();
        add(locations, location);
        for (JsonNode secondary : item.path("secondaryLocations")) {
            add(locations, secondary.path("location").asText(null));
        }
        JsonNode address = item.path("address").path("postalAddress");
        add(locations, join(address.path("addressLocality").asText(null),
                address.path("addressRegion").asText(null),
                address.path("addressCountry").asText(null)));
        String description = plainText(item.path("descriptionPlain")
                .asText(item.path("descriptionHtml").asText("")));
        return new RawJob(getSourceName(), item.path("id").asText(null),
                item.path("jobUrl").asText(), item.path("title").asText(), board,
                location, description, item.path("employmentType").asText(null),
                parseInstant(item.path("publishedAt").asText(null)), null, item.toString(),
                new RawLocationData(item.path("workplaceType").asText(
                        item.path("isRemote").asBoolean(false) ? "Remote" : null),
                        locations, List.of(), null, List.of(), null, null), board,
                new RawCareerData(firstText(item, "jobLevel", "seniority"),
                        null, null, null, null));
    }

    private String firstText(JsonNode item, String... fields) {
        for (String field : fields) {
            String value = item.path(field).asText(null);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private void add(List<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.strip());
    }

    private String join(String... parts) {
        return java.util.Arrays.stream(parts).filter(value -> value != null && !value.isBlank())
                .distinct().reduce((left, right) -> left + ", " + right).orElse(null);
    }

    private Instant parseInstant(String value) {
        try {
            return value == null || value.isBlank() ? null : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String plainText(String html) {
        String withoutTags = HtmlUtils.htmlUnescape(html).replaceAll("<[^>]+>", " ");
        return withoutTags.replaceAll("\\s+", " ").trim();
    }
}
