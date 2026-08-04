package com.jobpilot.sources.workday;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawCareerData;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.health.TenantFetchMonitor;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Public, bounded Workday CXS adapter.
 *
 * <p>One generic code path serves every tenant. The only real per-tenant difference — the
 * name of the country facet — is discovered at runtime by {@link WorkdayFacetResolver}
 * rather than configured. The Romania facet is applied server-side, which is what keeps
 * the fetch bounded: it turns thousands of postings into hundreds before a single detail
 * request is made.
 *
 * <p>The adapter performs no eligibility decisions. It reports every location Workday
 * supplies — primary, additional, country, and the search summary text — and leaves
 * screening to the existing pipeline.
 */
@Component
public class WorkdayJobSource implements JobSource {
    /** Workday rejects any larger page with HTTP 400. */
    static final int PAGE_SIZE = 20;
    /**
     * The posting cap is the binding bound on how much one career site may contribute; the
     * page cap is a separate safety net for pathological paging (short pages, stalled
     * offsets). They are deliberately not aligned, so 20 x 20 = 400 leaves the 300-posting
     * cap reachable instead of making it dead configuration.
     */
    static final int MAX_LIST_PAGES_PER_SITE = 20;
    static final int MAX_UNIQUE_JOBS_PER_SITE = 300;
    static final int MAX_DETAIL_REQUESTS_PER_SITE = 300;
    static final Duration MAX_DURATION_PER_SITE = Duration.ofMinutes(3);

    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");
    private static final int MAX_DESCRIPTION_CHARACTERS = 100_000;
    private static final int MAX_RAW_PAYLOAD_CHARACTERS = 20_000;

    private final ExternalHttpClient http;
    private final UrlCanonicalizer canonicalizer;
    private final WorkdayFacetResolver facets;
    private final TenantFetchMonitor monitor;
    private final List<WorkdayCareerSite> sites;

    @Autowired
    public WorkdayJobSource(ExternalHttpClient http, UrlCanonicalizer canonicalizer,
                            WorkdayFacetResolver facets, JobPilotProperties properties,
                            TenantFetchMonitor monitor) {
        this.http = http;
        this.canonicalizer = canonicalizer;
        this.facets = facets;
        this.monitor = monitor;
        this.sites = properties.sources().workdayCareerSites().stream()
                .map(WorkdayCareerSite::parse).toList();
    }

    public WorkdayJobSource(ExternalHttpClient http, UrlCanonicalizer canonicalizer,
                            WorkdayFacetResolver facets, JobPilotProperties properties) {
        this(http, canonicalizer, facets, properties, TenantFetchMonitor.disabled());
    }

    @Override
    public String getSourceName() {
        return "workday";
    }

    @Override
    public List<RawJob> fetchJobs() {
        // No configured career site means no HTTP call at all.
        List<RawJob> jobs = new ArrayList<>();
        for (WorkdayCareerSite site : sites) {
            jobs.addAll(monitor.fetch(getSourceName(), site.tenantKey(), () -> fetchSite(site)));
        }
        return List.copyOf(jobs);
    }

    List<RawJob> fetchSite(WorkdayCareerSite site) {
        long deadline = System.nanoTime() + MAX_DURATION_PER_SITE.toNanos();

        // One bootstrap request purely to learn this tenant's country facet name.
        JsonNode bootstrap = search(site, null, 1, 0, "bootstrap");
        String countryFacet = facets.resolveCountryFacet(bootstrap.path("facets")).orElse(null);

        Map<String, Summary> summaries = page(site, countryFacet, deadline);

        List<RawJob> jobs = new ArrayList<>(summaries.size());
        Set<String> postingIds = new LinkedHashSet<>();
        int detailRequests = 0;
        for (Summary summary : summaries.values()) {
            requireWithinDeadline(deadline);
            if (detailRequests >= MAX_DETAIL_REQUESTS_PER_SITE) {
                throw new WorkdayLimitException(
                        WorkdayLimitException.Limit.DETAIL_REQUESTS, MAX_DETAIL_REQUESTS_PER_SITE);
            }
            detailRequests++;
            Detail detail = detail(site, summary.externalPath());
            // Identity is the Workday GUID, never the slug or the title.
            if (!postingIds.add(detail.id())) {
                schemaFailure("detail.id repeated across distinct postings");
            }
            jobs.add(map(site, summary, detail));
        }
        return List.copyOf(jobs);
    }

    /** Offset pagination with no gaps and no repeated offsets. */
    private Map<String, Summary> page(WorkdayCareerSite site, String countryFacet, long deadline) {
        Map<String, Summary> summaries = new LinkedHashMap<>();
        Set<Integer> requestedOffsets = new LinkedHashSet<>();
        int offset = 0;
        int pages = 0;
        Integer total = null;
        while (true) {
            requireWithinDeadline(deadline);
            if (pages >= MAX_LIST_PAGES_PER_SITE) {
                throw new WorkdayLimitException(
                        WorkdayLimitException.Limit.LIST_PAGES, MAX_LIST_PAGES_PER_SITE);
            }
            String stage = "list[page" + (pages + 1) + "]";
            if (!requestedOffsets.add(offset)) schemaFailure(stage + " repeated offset");

            JsonNode page = search(site, countryFacet, PAGE_SIZE, offset, stage);
            pages++;
            // Workday populates total on the first page only; later pages report zero.
            if (offset == 0) total = requiredInt(page, "total", stage);

            List<Summary> items = summaries(page, stage);
            if (items.size() > PAGE_SIZE) schemaFailure(stage + " returned more than the page size");
            if (items.isEmpty()) return summaries;

            for (Summary item : items) {
                if (!summaries.containsKey(item.externalPath())
                        && summaries.size() >= MAX_UNIQUE_JOBS_PER_SITE) {
                    throw new WorkdayLimitException(
                            WorkdayLimitException.Limit.UNIQUE_POSTINGS, MAX_UNIQUE_JOBS_PER_SITE);
                }
                summaries.putIfAbsent(item.externalPath(), item);
            }

            int nextOffset;
            try {
                nextOffset = Math.addExact(offset, items.size());
            } catch (ArithmeticException overflow) {
                return schemaFailure(stage + " offset overflow");
            }
            if (nextOffset <= offset) schemaFailure(stage + " offset did not advance");
            // A short page is the last page; otherwise stop once the reported total is covered.
            if (items.size() < PAGE_SIZE) return summaries;
            if (total != null && nextOffset >= total) return summaries;
            offset = nextOffset;
        }
    }

    private JsonNode search(WorkdayCareerSite site, String countryFacet, int limit, int offset,
                            String stage) {
        Map<String, Object> appliedFacets = countryFacet == null || countryFacet.isBlank()
                ? Map.of()
                : Map.of(countryFacet, List.of(WorkdayFacetResolver.ROMANIA_COUNTRY_ID));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appliedFacets", appliedFacets);
        body.put("limit", limit);
        body.put("offset", offset);
        body.put("searchText", "");
        JsonNode root = http.postJson(site.searchUrl(), body);
        requireObject(root, stage + ".root");
        return root;
    }

    private List<Summary> summaries(JsonNode page, String stage) {
        JsonNode postings = page.get("jobPostings");
        if (postings == null || !postings.isArray()) {
            return schemaFailure(stage + ".jobPostings is not an array");
        }
        List<Summary> items = new ArrayList<>(postings.size());
        for (JsonNode node : postings) {
            requireObject(node, stage + ".jobPostings[]");
            // bulletFields is tenant-configured display text and carries no stable meaning,
            // so it is deliberately never read.
            items.add(new Summary(requiredText(node, "externalPath", stage),
                    optionalText(node, "title"), optionalText(node, "locationsText")));
        }
        return items;
    }

    private Detail detail(WorkdayCareerSite site, String externalPath) {
        JsonNode root = http.getJson(site.detailUrl(externalPath));
        requireObject(root, "detail.root");
        JsonNode info = root.get("jobPostingInfo");
        requireObject(info, "detail.jobPostingInfo");
        return new Detail(
                requiredText(info, "id", "detail"),
                requiredText(info, "title", "detail"),
                optionalText(info, "jobDescription"),
                optionalText(info, "location"),
                textList(info.get("additionalLocations")),
                optionalText(info.path("country"), "descriptor"),
                optionalText(info, "startDate"),
                optionalText(info, "endDate"),
                optionalText(info, "timeType"),
                optionalText(info, "externalUrl"),
                optionalText(root.path("hiringOrganization"), "name"),
                root.toString());
    }

    private RawJob map(WorkdayCareerSite site, Summary summary, Detail detail) {
        String canonicalUrl = canonicalUrl(site, detail, summary);
        String company = detail.company() == null || detail.company().isBlank()
                ? site.tenant() : detail.company();
        return new RawJob(
                getSourceName(),
                detail.id(),
                canonicalUrl,
                detail.title(),
                company,
                detail.location(),
                description(detail.jobDescription()),
                detail.timeType(),
                date(detail.startDate()),
                date(detail.endDate()),
                truncate(detail.rawPayload(), MAX_RAW_PAYLOAD_CHARACTERS),
                locationData(summary, detail),
                site.tenantKey(),
                RawCareerData.empty());
    }

    /**
     * Every location fact Workday exposes, so a posting whose primary office sits outside
     * Romania is still visible to screening when Bucharest appears among its additional
     * locations. Nothing is filtered out here.
     */
    private RawLocationData locationData(Summary summary, Detail detail) {
        List<String> locations = new ArrayList<>();
        locations.add(detail.location());
        locations.addAll(detail.additionalLocations());
        locations.add(detail.country());
        // "2 Locations" is a placeholder rather than a place, so it is not treated as one.
        if (summary.locationsText() != null && !summary.locationsText().isBlank()
                && !summary.locationsText().matches("(?i)\\d+\\s+locations?")) {
            locations.add(summary.locationsText());
        }
        return new RawLocationData(null, locations, List.of(), null, List.of(), null, null);
    }

    /**
     * The employer's own Workday application URL. A value that is not on this career site's
     * validated host is discarded and rebuilt, so no third-party or aggregator URL can be
     * persisted as the canonical link.
     */
    private String canonicalUrl(WorkdayCareerSite site, Detail detail, Summary summary) {
        String external = detail.externalUrl();
        if (external != null && !external.isBlank()) {
            java.net.URI canonical = canonicalizer.canonicalize(external);
            if (canonical != null && onSiteHost(canonical, site)) return canonical.toString();
        }
        String rebuilt = "https://" + site.host() + "/" + site.careerSite() + summary.externalPath();
        java.net.URI canonical = canonicalizer.canonicalize(rebuilt);
        return canonical == null ? rebuilt : canonical.toString();
    }

    private boolean onSiteHost(java.net.URI parsed, WorkdayCareerSite site) {
        return "https".equalsIgnoreCase(parsed.getScheme())
                && parsed.getHost() != null
                && parsed.getHost().equalsIgnoreCase(site.host());
    }

    private String description(String html) {
        if (html == null || html.isBlank()) return "";
        String text = HtmlUtils.htmlUnescape(TAGS.matcher(html).replaceAll(" "));
        return truncate(WHITESPACE.matcher(text).replaceAll(" ").strip(),
                MAX_DESCRIPTION_CHARACTERS);
    }

    private Instant date(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            return LocalDate.parse(isoDate.strip()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    private void requireWithinDeadline(long deadlineNanos) {
        if (System.nanoTime() - deadlineNanos >= 0) {
            throw new WorkdayLimitException(WorkdayLimitException.Limit.RUNTIME_SECONDS,
                    MAX_DURATION_PER_SITE.toSeconds());
        }
    }

    private List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) if (item.isTextual()) values.add(item.asText());
        return values;
    }

    private void requireObject(JsonNode node, String stage) {
        if (node == null || !node.isObject()) schemaFailure(stage + " is not an object");
    }

    private String requiredText(JsonNode node, String field, String stage) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            return schemaFailure(stage + "." + field + " is missing or blank");
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private int requiredInt(JsonNode node, String field, String stage) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || value.asInt() < 0) {
            return schemaFailure(stage + "." + field + " is not a non-negative integer");
        }
        return value.asInt();
    }

    /** Provider-generic field-path text only; never a response value. */
    private <T> T schemaFailure(String detail) {
        throw new ExternalHttpException(ExternalHttpException.Category.MALFORMED_JSON, null)
                .parseDetail(detail);
    }

    private String truncate(String value, int maximum) {
        if (value == null) return null;
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record Summary(String externalPath, String title, String locationsText) {
    }

    private record Detail(String id, String title, String jobDescription, String location,
                          List<String> additionalLocations, String country, String startDate,
                          String endDate, String timeType, String externalUrl, String company,
                          String rawPayload) {
    }
}
