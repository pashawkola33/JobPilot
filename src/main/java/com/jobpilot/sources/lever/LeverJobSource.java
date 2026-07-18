package com.jobpilot.sources.lever;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.sources.JobSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class LeverJobSource implements JobSource {
    private final ExternalHttpClient http;
    private final List<String> companies;

    public LeverJobSource(ExternalHttpClient http, JobPilotProperties properties) {
        this.http = http;
        this.companies = properties.sources().leverCompanyIds();
    }

    @Override
    public String getSourceName() {
        return "lever";
    }

    @Override
    public List<RawJob> fetchJobs() {
        List<RawJob> jobs = new ArrayList<>();
        for (String company : companies) {
            jobs.addAll(parse(company, http.getJson("https://api.lever.co/v0/postings/" + company + "?mode=json")));
        }
        return jobs;
    }

    public List<RawJob> parse(String company, JsonNode root) {
        List<RawJob> result = new ArrayList<>();
        for (JsonNode item : root) {
            result.add(parseOne(company, item));
        }
        return result;
    }

    public RawJob parseOne(String company, JsonNode item) {
        String rawDescription = item.path("descriptionPlain").asText(item.path("description").asText(""));
        return new RawJob(getSourceName(), item.path("id").asText(),
                item.path("hostedUrl").asText(), item.path("text").asText(), company,
                item.path("categories").path("location").asText(""),
                plainText(rawDescription), item.path("categories").path("commitment").asText(null),
                item.hasNonNull("createdAt") ? Instant.ofEpochMilli(item.path("createdAt").asLong()) : null,
                null, item.toString());
    }

    private String plainText(String html) {
        // Unescape before stripping tags so escaped markup (&lt;p&gt;) is removed as well.
        String withoutTags = HtmlUtils.htmlUnescape(html).replaceAll("<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(withoutTags).replaceAll("\\s+", " ").trim();
    }
}
