package com.jobpilot.sources.recruitee;

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
public class RecruiteeJobSource implements JobSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(RecruiteeJobSource.class);
    private final ExternalHttpClient http;
    private final List<String> companies;

    public RecruiteeJobSource(ExternalHttpClient http, JobPilotProperties properties) {
        this.http = http;
        this.companies = properties.sources().recruiteeCompanyIds();
    }

    @Override
    public String getSourceName() {
        return "recruitee";
    }

    @Override
    public List<RawJob> fetchJobs() {
        List<RawJob> jobs = new ArrayList<>();
        for (String company : companies) {
            try {
                jobs.addAll(parse(company,
                        http.getJson("https://" + company + ".recruitee.com/api/offers/")));
            } catch (RuntimeException failure) {
                LOGGER.warn("Recruitee tenant {} failed: {}", company,
                        failure.getClass().getSimpleName());
            }
        }
        return jobs;
    }

    public List<RawJob> parse(String company, JsonNode root) {
        List<RawJob> result = new ArrayList<>();
        for (JsonNode item : root.path("offers")) result.add(parseOne(company, item));
        return result;
    }

    public RawJob parseOne(String company, JsonNode item) {
        List<String> locations = new ArrayList<>();
        add(locations, item.path("location").asText(null));
        add(locations, join(item.path("city").asText(null), item.path("state_name").asText(null),
                item.path("country").asText(null)));
        for (JsonNode value : item.path("locations")) {
            add(locations, join(value.path("city").asText(null), value.path("state").asText(null),
                    value.path("country").asText(null), value.path("note").asText(null)));
        }
        String workplace = item.path("remote").asBoolean(false) ? "Remote"
                : item.path("hybrid").asBoolean(false) ? "Hybrid"
                : item.path("on_site").asBoolean(false) ? "OnSite" : null;
        String description = plainText(joinText(item.path("description").asText(null),
                item.path("requirements").asText(null)));
        RawCareerData careerData = careerData(item.path("experience_code").asText(null));
        return new RawJob(getSourceName(), item.path("id").asText(item.path("guid").asText(null)),
                item.path("careers_url").asText(), item.path("title").asText(
                        item.path("position").asText()),
                item.path("company_name").asText(company), item.path("location").asText(""),
                description, item.path("employment_type_code").asText(null),
                parseInstant(item.path("published_at").asText(null)),
                parseInstant(item.path("close_at").asText(null)), item.toString(),
                new RawLocationData(workplace, locations, List.of(), null, List.of(), null, null),
                company, careerData);
    }

    private RawCareerData careerData(String code) {
        if (code == null || code.isBlank()) return RawCareerData.empty();
        String normalized = code.toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (normalized) {
            case "no_experience", "none" -> new RawCareerData(code, 0.0, 0.0, true, code);
            case "one_year", "1_year", "one_year_or_less" ->
                    new RawCareerData(code, 0.0, 1.0, true, code);
            case "two_years", "2_years", "one_to_two_years" ->
                    new RawCareerData(code, 1.0, 2.0, true, code);
            case "three_years", "3_years", "three_to_four_years" ->
                    new RawCareerData(code, 3.0, 4.0, true, code);
            case "four_years", "4_years", "more_than_four_years", "five_years", "5_years" ->
                    new RawCareerData(code, 4.0, null, true, code);
            default -> new RawCareerData(code, null, null, null, null);
        };
    }

    private void add(List<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.strip());
    }

    private String join(String... parts) {
        return java.util.Arrays.stream(parts).filter(value -> value != null && !value.isBlank())
                .distinct().reduce((left, right) -> left + ", " + right).orElse(null);
    }

    private String joinText(String first, String second) {
        return (first == null ? "" : first) + " " + (second == null ? "" : second);
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
        return HtmlUtils.htmlUnescape(withoutTags).replaceAll("\\s+", " ").trim();
    }
}
