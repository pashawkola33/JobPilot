package com.jobpilot.sources.lever;

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
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class LeverJobSource implements JobSource {
    private final ExternalHttpClient http;
    private final List<String> companies;
    private final TenantFetchMonitor monitor;

    @Autowired
    public LeverJobSource(ExternalHttpClient http, JobPilotProperties properties,
                          TenantFetchMonitor monitor) {
        this.http = http;
        this.companies = properties.sources().leverCompanyIds();
        this.monitor = monitor;
    }

    public LeverJobSource(ExternalHttpClient http, JobPilotProperties properties) {
        this(http, properties, TenantFetchMonitor.disabled());
    }

    @Override
    public String getSourceName() {
        return "lever";
    }

    @Override
    public List<RawJob> fetchJobs() {
        List<RawJob> jobs = new ArrayList<>();
        // One monitored attempt per tenant: a failure is classified, recorded, and
        // isolated to that tenant so the remaining companies are still fetched.
        for (String company : companies) {
            jobs.addAll(monitor.fetch(getSourceName(), company, () -> parse(company, http.getJson(
                    "https://api.lever.co/v0/postings/" + company + "?mode=json"))));
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
        String rawDescription = fullDescription(item);
        String location = item.path("categories").path("location").asText("");
        return new RawJob(getSourceName(), item.path("id").asText(),
                item.path("hostedUrl").asText(), item.path("text").asText(), company,
                location,
                plainText(rawDescription), item.path("categories").path("commitment").asText(null),
                item.hasNonNull("createdAt") ? Instant.ofEpochMilli(item.path("createdAt").asLong()) : null,
                null, item.toString(),
                new RawLocationData(item.path("workplaceType").asText(null),
                        List.of(location), List.of(), null, List.of(), null, null), company,
                new RawCareerData(item.path("categories").path("level").asText(null),
                        null, null, null, null));
    }

    private String fullDescription(JsonNode item) {
        List<String> sections = new ArrayList<>();
        add(sections, item.path("descriptionPlain").asText(item.path("description").asText(null)));
        for (JsonNode list : item.path("lists")) {
            add(sections, list.path("text").asText(null));
            add(sections, list.path("content").asText(null));
        }
        add(sections, item.path("additionalPlain").asText(item.path("additional").asText(null)));
        return String.join(" ", sections);
    }

    private void add(List<String> sections, String value) {
        if (value != null && !value.isBlank()) sections.add(value);
    }

    private String plainText(String html) {
        String withoutTags = HtmlUtils.htmlUnescape(html).replaceAll("<[^>]+>", " ");
        return withoutTags.replaceAll("\\s+", " ").trim();
    }
}
