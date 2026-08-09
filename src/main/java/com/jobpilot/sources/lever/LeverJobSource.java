package com.jobpilot.sources.lever;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawCareerData;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.health.TenantFetchMonitor;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Public, bounded Lever Postings API adapter.
 *
 * <p>Lever returns one flat JSON array per request, so an unpaginated tenant fetch grows
 * with the board: the largest configured tenant broke the shared response-size bound and
 * failed as {@code RESPONSE_TOO_LARGE}. Every tenant is therefore fetched in fixed-size
 * {@code skip}/{@code limit} pages, which keeps each response small without touching the
 * transport limit.
 */
@Component
public class LeverJobSource implements JobSource {
    /**
     * Conservative fixed page size. Lever accepts a larger {@code limit}, but response
     * bytes scale with it, and a Lever posting carries its complete description; 50 keeps
     * every page far below the shared transport bound even for verbose boards.
     */
    static final int PAGE_SIZE = 50;

    /**
     * Hard stop on requests per tenant, and therefore on postings: at most
     * {@code 40 x 50 = 2000} postings per board, comfortably above every configured tenant
     * while keeping a pathological or hostile board from paging forever.
     */
    static final int MAX_PAGES_PER_TENANT = 40;

    private static final String API_ROOT = "https://api.lever.co/v0/postings/";

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
        // One monitored attempt per tenant, covering the tenant's complete pagination: a
        // failure on any page is classified, recorded, and isolated to that tenant, so no
        // partial board escapes and the remaining companies are still fetched.
        for (String company : companies) {
            jobs.addAll(monitor.fetch(getSourceName(), company, () -> fetchCompany(company)));
        }
        return jobs;
    }

    /**
     * Pages one tenant with {@code skip} and {@code limit}, in provider order.
     *
     * <p>{@code skip} advances by the number of postings the page actually returned rather
     * than by the requested page size. Both agree on a full page, but only the returned
     * count is safe if Lever ever trims a page: stepping by the constant could then skip
     * over postings that were never read. Paging stops on the first empty or short page,
     * postings repeated across pages are returned once, and a full page that contributes
     * nothing new means the provider ignored {@code skip} — rejected rather than retried.
     */
    List<RawJob> fetchCompany(String company) {
        List<RawJob> jobs = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        int skip = 0;
        for (int page = 1; ; page++) {
            if (page > MAX_PAGES_PER_TENANT) {
                // Structural cap, reported with the existing oversize semantics: it carries
                // no URL, response, or posting data.
                throw new ExternalHttpException(
                        ExternalHttpException.Category.RESPONSE_TOO_LARGE, null);
            }
            String stage = "page" + page;
            JsonNode items = http.getJson(pageUrl(company, skip));
            if (!items.isArray()) {
                schemaFailure(stage + ": expected ARRAY but was " + items.getNodeType().name());
            }
            if (items.size() > PAGE_SIZE) {
                schemaFailure(stage + " returned more postings than the requested limit");
            }
            if (items.isEmpty()) return List.copyOf(jobs);

            int before = jobs.size();
            for (JsonNode item : items) {
                if (seenIds.add(item.path("id").asText())) jobs.add(parseOne(company, item));
            }
            if (jobs.size() == before) {
                schemaFailure(stage + " repeated only postings earlier pages already returned");
            }
            if (items.size() < PAGE_SIZE) return List.copyOf(jobs);
            skip += items.size();
        }
    }

    private String pageUrl(String company, int skip) {
        return API_ROOT + company + "?mode=json&skip=" + skip + "&limit=" + PAGE_SIZE;
    }

    /**
     * Rejects a response and names the rule that rejected it. The detail is built only from
     * compile-time literals and a JSON <em>type</em> name, so it can never carry a field
     * value, response fragment, URL, or header.
     */
    private static void schemaFailure(String detail) {
        throw new ExternalHttpException(ExternalHttpException.Category.MALFORMED_JSON, null)
                .parseDetail(detail);
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
