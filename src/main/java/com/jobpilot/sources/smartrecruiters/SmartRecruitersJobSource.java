package com.jobpilot.sources.smartrecruiters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.ExternalHttpClient;
import com.jobpilot.common.ExternalHttpException;
import com.jobpilot.common.Hashing;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.RawCareerData;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.sources.JobSource;
import com.jobpilot.sources.health.TenantFetchMonitor;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

/** Public, bounded SmartRecruiters Posting API adapter. */
@Component
public class SmartRecruitersJobSource implements JobSource {
    static final int PAGE_SIZE = 100;
    static final int MAX_LIST_PAGES_PER_TENANT = 10;
    static final int MAX_UNIQUE_JOBS_PER_TENANT = 500;

    private static final String API_ROOT = "https://api.smartrecruiters.com/v1/companies/";
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern NON_SECTION_NAME = Pattern.compile("[^a-z]");

    private final ExternalHttpClient http;
    private final ObjectMapper mapper;
    private final UrlCanonicalizer canonicalizer;
    private final List<String> companies;
    private final TenantFetchMonitor monitor;
    private final String countryCode;

    @Autowired
    public SmartRecruitersJobSource(ExternalHttpClient http, ObjectMapper mapper,
                                    UrlCanonicalizer canonicalizer,
                                    JobPilotProperties properties, TenantFetchMonitor monitor) {
        this.http = http;
        this.mapper = mapper;
        this.canonicalizer = canonicalizer;
        this.companies = properties.sources().smartrecruitersCompanyIdentifiers();
        this.monitor = monitor;
        this.countryCode = countryCode(properties.eligibility().targetCountry());
    }

    public SmartRecruitersJobSource(ExternalHttpClient http, ObjectMapper mapper,
                                    UrlCanonicalizer canonicalizer,
                                    JobPilotProperties properties) {
        this(http, mapper, canonicalizer, properties, TenantFetchMonitor.disabled());
    }

    @Override
    public String getSourceName() {
        return "smartrecruiters";
    }

    @Override
    public List<RawJob> fetchJobs() {
        List<RawJob> jobs = new ArrayList<>();
        for (String company : companies) {
            jobs.addAll(monitor.fetch(getSourceName(), company, () -> fetchCompany(company)));
        }
        return List.copyOf(jobs);
    }

    List<RawJob> fetchCompany(String company) {
        FetchState state = new FetchState();
        fetchPartition(company, Partition.COUNTRY, state);
        fetchPartition(company, Partition.REMOTE, state);

        List<RawJob> result = new ArrayList<>(state.summaries.size());
        Set<String> detailIds = new LinkedHashSet<>();
        for (PostingSummary summary : state.summaries.values()) {
            PostingDetail detail = detail(company, summary.id());
            if (!detailIds.add(detail.id())) schemaFailure();
            validateDetailIdentity(company, summary, detail);
            result.add(map(company, detail));
        }
        return List.copyOf(result);
    }

    private void fetchPartition(String company, Partition partition, FetchState state) {
        int requestedOffset = 0;
        Set<Integer> requestedOffsets = new LinkedHashSet<>();
        Set<String> pageFingerprints = new LinkedHashSet<>();
        while (true) {
            if (state.pages >= MAX_LIST_PAGES_PER_TENANT) {
                throw new SmartRecruitersLimitException(
                        SmartRecruitersLimitException.Limit.LIST_PAGES,
                        MAX_LIST_PAGES_PER_TENANT);
            }
            if (!requestedOffsets.add(requestedOffset)) schemaFailure();
            PostingPage page = page(listUrl(company, partition, requestedOffset));
            state.pages = Math.addExact(state.pages, 1);
            validatePage(page, requestedOffset);

            if (page.content().isEmpty()) return;
            String fingerprint = fingerprint(page.content());
            if (!pageFingerprints.add(fingerprint)) schemaFailure();

            for (PostingSummary summary : page.content()) {
                validateSummaryTenant(company, summary);
                PostingSummary previous = state.summaries.get(summary.id());
                if (previous != null && !previous.compatibleWith(summary)) schemaFailure();
                if (previous == null) {
                    if (state.summaries.size() >= MAX_UNIQUE_JOBS_PER_TENANT) {
                        throw new SmartRecruitersLimitException(
                                SmartRecruitersLimitException.Limit.UNIQUE_POSTINGS,
                                MAX_UNIQUE_JOBS_PER_TENANT);
                    }
                    state.summaries.put(summary.id(), summary);
                }
            }

            int nextOffset;
            try {
                nextOffset = Math.addExact(requestedOffset, page.content().size());
            } catch (ArithmeticException overflow) {
                schemaFailure();
                return;
            }
            if (nextOffset <= requestedOffset || nextOffset > page.totalFound()) schemaFailure();
            if (nextOffset >= page.totalFound()) return;
            if (state.summaries.size() >= MAX_UNIQUE_JOBS_PER_TENANT) {
                throw new SmartRecruitersLimitException(
                        SmartRecruitersLimitException.Limit.UNIQUE_POSTINGS,
                        MAX_UNIQUE_JOBS_PER_TENANT);
            }
            requestedOffset = nextOffset;
        }
    }

    private PostingPage page(String url) {
        JsonNode root = http.getJson(url);
        requireObject(root);
        int limit = requiredInt(root, "limit");
        int offset = requiredInt(root, "offset");
        int totalFound = requiredInt(root, "totalFound");
        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) return schemaFailure();
        if (limit < 1 || limit > PAGE_SIZE || content.size() > limit) return schemaFailure();
        List<PostingSummary> summaries = new ArrayList<>(content.size());
        for (JsonNode item : content) summaries.add(summary(item));
        return new PostingPage(limit, offset, totalFound, List.copyOf(summaries));
    }

    private PostingSummary summary(JsonNode node) {
        requireObject(node);
        return new PostingSummary(requiredText(node, "id"), requiredText(node, "name"),
                optionalText(node, "uuid"), company(node), location(node),
                optionalText(node, "releasedDate"), label(node, "department"),
                label(node, "function"), label(node, "typeOfEmployment"),
                label(node, "experienceLevel"), optionalText(node, "ref"));
    }

    private PostingDetail detail(String company, String postingId) {
        JsonNode node = http.getJson(API_ROOT + company + "/postings/"
                + UriUtils.encodePathSegment(postingId, java.nio.charset.StandardCharsets.UTF_8));
        requireObject(node);
        return new PostingDetail(requiredText(node, "id"), requiredText(node, "name"),
                optionalText(node, "uuid"), company(node), location(node),
                optionalText(node, "releasedDate"), label(node, "department"),
                label(node, "function"), label(node, "typeOfEmployment"),
                label(node, "experienceLevel"), requiredText(node, "postingUrl"),
                optionalText(node, "applyUrl"), sections(node));
    }

    private void validatePage(PostingPage page, int requestedOffset) {
        if (page.limit() < 1 || page.limit() > PAGE_SIZE || page.offset() < 0
                || page.offset() != requestedOffset || page.totalFound() < 0
                || page.content().size() > page.limit()
                || page.offset() > page.totalFound()
                || !page.content().isEmpty() && page.offset() >= page.totalFound()) {
            schemaFailure();
        }
    }

    private void validateSummaryTenant(String tenant, PostingSummary summary) {
        if (!tenant.equals(summary.company().identifier())) schemaFailure();
        parseInstant(summary.releasedDate());
    }

    private void validateDetailIdentity(String tenant, PostingSummary summary,
                                        PostingDetail detail) {
        if (!summary.id().equals(detail.id())
                || !tenant.equals(detail.company().identifier())
                || conflict(summary.uuid(), detail.uuid())) {
            schemaFailure();
        }
    }

    private RawJob map(String tenant, PostingDetail detail) {
        String location = locationText(detail.location());
        String description = description(detail);
        if (description.isBlank()) return schemaFailure();
        String companyName = firstNonblank(detail.company().name(), tenant);
        String employment = labelText(detail.employmentType());
        String experience = labelText(detail.experienceLevel());
        String workplace = Boolean.TRUE.equals(detail.location().remote()) ? "Remote" : null;
        String structuredLocation = structuredLocationText(detail.location());
        List<String> structured = structuredLocation.isBlank()
                ? List.of() : List.of(structuredLocation);
        return new RawJob(getSourceName(), detail.id(), canonicalUrl(detail.postingUrl()),
                detail.name(), companyName, location, description, employment,
                parseInstant(detail.releasedDate()), null, rawPayload(detail),
                new RawLocationData(workplace, structured, List.of(), null, List.of(), null, null),
                tenant, new RawCareerData(experience, null, null, null, null));
    }

    private String description(PostingDetail detail) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addLabel(parts, "Department", detail.department());
        addLabel(parts, "Function", detail.function());
        String jobDescription = plainText(detail.sections().jobDescription());
        if (jobDescription.isBlank()) schemaFailure();
        add(parts, jobDescription);
        add(parts, plainText(detail.sections().qualifications()));
        add(parts, plainText(detail.sections().additionalInformation()));
        return String.join("\n", parts);
    }

    private String canonicalUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                return schemaFailure();
            }
            return canonicalizer.canonicalize(raw).toString();
        } catch (RuntimeException invalid) {
            return schemaFailure();
        }
    }

    private String rawPayload(PostingDetail detail) {
        try {
            return mapper.writeValueAsString(detail);
        } catch (JsonProcessingException impossible) {
            return schemaFailure();
        }
    }

    private Company company(JsonNode node) {
        JsonNode company = node.get("company");
        requireObject(company);
        return new Company(requiredText(company, "identifier"), optionalText(company, "name"));
    }

    private Location location(JsonNode node) {
        JsonNode location = node.get("location");
        if (location == null || location.isNull() || location.isMissingNode()) {
            return new Location(null, null, null, null);
        }
        requireObject(location);
        JsonNode remote = location.get("remote");
        if (remote != null && !remote.isNull() && !remote.isBoolean()) schemaFailure();
        return new Location(optionalText(location, "city"), optionalText(location, "region"),
                optionalText(location, "country"),
                remote == null || remote.isNull() ? null : remote.booleanValue());
    }

    private Label label(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return new Label(null, null);
        requireObject(value);
        return new Label(optionalText(value, "id"), optionalText(value, "label"));
    }

    private Sections sections(JsonNode detail) {
        JsonNode jobAd = detail.get("jobAd");
        requireObject(jobAd);
        JsonNode sections = jobAd.get("sections");
        if (sections == null || sections.isNull()) return schemaFailure();
        if (sections.isObject()) {
            return new Sections(sectionText(sections.get("companyDescription")),
                    sectionText(sections.get("jobDescription")),
                    sectionText(sections.get("qualifications")),
                    sectionText(sections.get("additionalInformation")));
        }
        if (!sections.isArray()) return schemaFailure();
        Map<String, String> byName = new LinkedHashMap<>();
        for (JsonNode section : sections) {
            requireObject(section);
            String name = firstNonblank(optionalText(section, "identifier"),
                    optionalText(section, "name"), optionalText(section, "title"));
            if (name == null) schemaFailure();
            String text = sectionText(section);
            byName.put(normalizeSectionName(name), text);
        }
        return new Sections(byName.get("companydescription"), byName.get("jobdescription"),
                byName.get("qualifications"), byName.get("additionalinformation"));
    }

    private String sectionText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.textValue();
        if (!node.isObject()) return schemaFailure();
        return optionalText(node, "text");
    }

    private String locationText(Location value) {
        String structured = structuredLocationText(value);
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        add(parts, structured);
        if (Boolean.TRUE.equals(value.remote())
                && parts.stream().noneMatch(part -> part.toLowerCase(Locale.ROOT)
                .contains("remote"))) {
            parts.add("Remote");
        }
        return String.join(", ", parts);
    }

    private String structuredLocationText(Location value) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        add(parts, value.city());
        add(parts, value.region());
        add(parts, value.country() == null ? null : value.country().toUpperCase(Locale.ROOT));
        return String.join(", ", parts);
    }

    private String listUrl(String company, Partition partition, int offset) {
        String filter = partition == Partition.COUNTRY
                ? "country=" + countryCode : "q=remote";
        return API_ROOT + company + "/postings?limit=" + PAGE_SIZE + "&offset=" + offset
                + "&" + filter;
    }

    private String fingerprint(List<PostingSummary> content) {
        return Hashing.sha256(content.stream().map(PostingSummary::id)
                .reduce((left, right) -> left + "\u001f" + right).orElse(""));
    }

    private static String countryCode(String configured) {
        if (configured != null && configured.strip().equalsIgnoreCase("Romania")) return "ro";
        throw new IllegalArgumentException(
                "SmartRecruiters requires a supported ISO country mapping for target-country");
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return schemaFailure();
        }
        return value.intValue();
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) return schemaFailure();
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        if (node == null) return schemaFailure();
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (!value.isTextual()) return schemaFailure();
        return value.textValue();
    }

    private void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) schemaFailure();
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            return schemaFailure();
        }
    }

    private String plainText(String html) {
        if (html == null || html.isBlank()) return "";
        String unescaped = HtmlUtils.htmlUnescape(html);
        return WHITESPACE.matcher(TAGS.matcher(unescaped).replaceAll(" "))
                .replaceAll(" ").strip();
    }

    private void addLabel(Set<String> parts, String name, Label value) {
        String text = labelText(value);
        if (text != null) add(parts, name + ": " + text);
    }

    private String labelText(Label value) {
        return value == null ? null : firstNonblank(value.label(), value.id());
    }

    private void add(Set<String> parts, String value) {
        if (value != null && !value.isBlank()) parts.add(value.strip());
    }

    private String firstNonblank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.strip();
        }
        return null;
    }

    private boolean conflict(String left, String right) {
        return left != null && right != null && !left.equals(right);
    }

    private String normalizeSectionName(String value) {
        return NON_SECTION_NAME.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static <T> T schemaFailure() {
        throw new ExternalHttpException(ExternalHttpException.Category.MALFORMED_JSON, null);
    }

    private enum Partition { COUNTRY, REMOTE }

    private static final class FetchState {
        private final LinkedHashMap<String, PostingSummary> summaries = new LinkedHashMap<>();
        private int pages;
    }

    private record PostingPage(int limit, int offset, int totalFound,
                               List<PostingSummary> content) { }

    private record PostingSummary(String id, String name, String uuid, Company company,
                                  Location location, String releasedDate, Label department,
                                  Label function, Label employmentType, Label experienceLevel,
                                  String ref) {
        private boolean compatibleWith(PostingSummary other) {
            return id.equals(other.id) && name.equals(other.name)
                    && company.identifier.equals(other.company.identifier)
                    && compatible(uuid, other.uuid);
        }

        private static boolean compatible(String left, String right) {
            return left == null || right == null || left.equals(right);
        }
    }

    private record PostingDetail(String id, String name, String uuid, Company company,
                                 Location location, String releasedDate, Label department,
                                 Label function, Label employmentType, Label experienceLevel,
                                 String postingUrl, String applyUrl, Sections sections) { }

    private record Company(String identifier, String name) { }
    private record Location(String city, String region, String country, Boolean remote) { }
    private record Label(String id, String label) { }
    private record Sections(String companyDescription, String jobDescription,
                            String qualifications, String additionalInformation) { }
}
