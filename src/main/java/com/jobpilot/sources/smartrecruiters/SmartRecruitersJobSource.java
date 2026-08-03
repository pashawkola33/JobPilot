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
            if (!detailIds.add(detail.id())) {
                schemaFailure("detail.id repeated across distinct postings");
            }
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
            String stage = "list[" + partition + ",page" + (state.pages + 1) + "]";
            if (!requestedOffsets.add(requestedOffset)) {
                schemaFailure(stage + " repeated offset");
            }
            PostingPage page = page(listUrl(company, partition, requestedOffset), stage);
            state.pages = Math.addExact(state.pages, 1);
            validatePage(page, requestedOffset, stage);

            if (page.content().isEmpty()) return;
            String fingerprint = fingerprint(page.content());
            if (!pageFingerprints.add(fingerprint)) {
                schemaFailure(stage + " repeated page fingerprint");
            }

            for (PostingSummary summary : page.content()) {
                validateSummaryTenant(company, summary, stage);
                PostingSummary previous = state.summaries.get(summary.id());
                if (previous != null && !previous.compatibleWith(summary)) {
                    schemaFailure(stage + " duplicate posting id with conflicting fields");
                }
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
                schemaFailure(stage + " offset overflow");
                return;
            }
            if (nextOffset <= requestedOffset) schemaFailure(stage + " offset did not advance");
            if (nextOffset > page.totalFound()) {
                schemaFailure(stage + " content exceeds totalFound");
            }
            if (nextOffset >= page.totalFound()) return;
            if (state.summaries.size() >= MAX_UNIQUE_JOBS_PER_TENANT) {
                throw new SmartRecruitersLimitException(
                        SmartRecruitersLimitException.Limit.UNIQUE_POSTINGS,
                        MAX_UNIQUE_JOBS_PER_TENANT);
            }
            requestedOffset = nextOffset;
        }
    }

    private PostingPage page(String url, String stage) {
        JsonNode root = http.getJson(url);
        requireObject(root, stage + ".root");
        int limit = requiredInt(root, "limit", stage);
        int offset = requiredInt(root, "offset", stage);
        int totalFound = requiredInt(root, "totalFound", stage);
        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) {
            return schemaFailure(stage + ".content", "ARRAY", content);
        }
        if (limit < 1 || limit > PAGE_SIZE) return schemaFailure(stage + ".limit out of 1.." + PAGE_SIZE);
        if (content.size() > limit) return schemaFailure(stage + ".content longer than limit");
        List<PostingSummary> summaries = new ArrayList<>(content.size());
        for (JsonNode item : content) summaries.add(summary(item, stage + ".content[]"));
        return new PostingPage(limit, offset, totalFound, List.copyOf(summaries));
    }

    private PostingSummary summary(JsonNode node, String stage) {
        requireObject(node, stage);
        return new PostingSummary(requiredText(node, "id", stage), requiredText(node, "name", stage),
                optionalText(node, "uuid", stage), company(node, stage), location(node, stage),
                optionalText(node, "releasedDate", stage), label(node, "department", stage),
                label(node, "function", stage), label(node, "typeOfEmployment", stage),
                label(node, "experienceLevel", stage), optionalText(node, "ref", stage));
    }

    private PostingDetail detail(String company, String postingId) {
        JsonNode node = http.getJson(API_ROOT + company + "/postings/"
                + UriUtils.encodePathSegment(postingId, java.nio.charset.StandardCharsets.UTF_8));
        requireObject(node, "detail");
        return new PostingDetail(requiredText(node, "id", "detail"), requiredText(node, "name", "detail"),
                optionalText(node, "uuid", "detail"), company(node, "detail"), location(node, "detail"),
                optionalText(node, "releasedDate", "detail"), label(node, "department", "detail"),
                label(node, "function", "detail"), label(node, "typeOfEmployment", "detail"),
                label(node, "experienceLevel", "detail"), requiredText(node, "postingUrl", "detail"),
                optionalText(node, "applyUrl", "detail"), sections(node, "detail"));
    }

    private void validatePage(PostingPage page, int requestedOffset, String stage) {
        if (page.limit() < 1 || page.limit() > PAGE_SIZE) {
            schemaFailure(stage + " limit outside 1.." + PAGE_SIZE);
        }
        if (page.offset() < 0 || page.offset() != requestedOffset) {
            schemaFailure(stage + " returned an offset other than the requested one");
        }
        if (page.totalFound() < 0) schemaFailure(stage + " negative totalFound");
        if (page.content().size() > page.limit()) {
            schemaFailure(stage + " content longer than limit");
        }
        if (page.offset() > page.totalFound()
                || !page.content().isEmpty() && page.offset() >= page.totalFound()) {
            schemaFailure(stage + " offset inconsistent with totalFound");
        }
    }

    private void validateSummaryTenant(String tenant, PostingSummary summary, String stage) {
        if (!tenant.equals(summary.company().identifier())) {
            schemaFailure(stage + ".company.identifier differs from the configured tenant");
        }
        parseInstant(summary.releasedDate(), stage);
    }

    private void validateDetailIdentity(String tenant, PostingSummary summary,
                                        PostingDetail detail) {
        if (!summary.id().equals(detail.id())) {
            schemaFailure("detail.id does not match the requested posting id");
        }
        if (!tenant.equals(detail.company().identifier())) {
            schemaFailure("detail.company.identifier differs from the configured tenant");
        }
        if (conflict(summary.uuid(), detail.uuid())) {
            schemaFailure("detail.uuid conflicts with the list uuid");
        }
    }

    private RawJob map(String tenant, PostingDetail detail) {
        String location = locationText(detail.location());
        String description = description(detail);
        if (description.isBlank()) return schemaFailure("detail description is blank");
        String companyName = firstNonblank(detail.company().name(), tenant);
        String employment = labelText(detail.employmentType());
        String experience = labelText(detail.experienceLevel());
        String workplace = Boolean.TRUE.equals(detail.location().remote()) ? "Remote" : null;
        String structuredLocation = structuredLocationText(detail.location());
        List<String> structured = structuredLocation.isBlank()
                ? List.of() : List.of(structuredLocation);
        return new RawJob(getSourceName(), detail.id(), canonicalUrl(detail.postingUrl()),
                detail.name(), companyName, location, description, employment,
                parseInstant(detail.releasedDate(), "detail"), null, rawPayload(detail),
                new RawLocationData(workplace, structured, List.of(), null, List.of(), null, null),
                tenant, new RawCareerData(experience, null, null, null, null));
    }

    private String description(PostingDetail detail) {
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        addLabel(parts, "Department", detail.department());
        addLabel(parts, "Function", detail.function());
        String jobDescription = plainText(detail.sections().jobDescription());
        if (jobDescription.isBlank()) {
            schemaFailure("detail.jobAd.sections.jobDescription is absent or empty");
        }
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
                return schemaFailure("detail.postingUrl is not an absolute https URL without userinfo");
            }
            return canonicalizer.canonicalize(raw).toString();
        } catch (RuntimeException invalid) {
            return schemaFailure("detail.postingUrl is not a usable URL");
        }
    }

    private String rawPayload(PostingDetail detail) {
        try {
            return mapper.writeValueAsString(detail);
        } catch (JsonProcessingException impossible) {
            return schemaFailure("detail could not be reserialized");
        }
    }

    private Company company(JsonNode node, String stage) {
        JsonNode company = node.get("company");
        requireObject(company, stage + ".company");
        return new Company(requiredText(company, "identifier", stage + ".company"),
                optionalText(company, "name", stage + ".company"));
    }

    private Location location(JsonNode node, String stage) {
        JsonNode location = node.get("location");
        if (location == null || location.isNull() || location.isMissingNode()) {
            return new Location(null, null, null, null);
        }
        requireObject(location, stage + ".location");
        JsonNode remote = location.get("remote");
        if (remote != null && !remote.isNull() && !remote.isBoolean()) {
            schemaFailure(stage + ".location.remote", "BOOLEAN", remote);
        }
        return new Location(optionalText(location, "city", stage + ".location"),
                optionalText(location, "region", stage + ".location"),
                optionalText(location, "country", stage + ".location"),
                remote == null || remote.isNull() ? null : remote.booleanValue());
    }

    private Label label(JsonNode node, String field, String stage) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return new Label(null, null);
        requireObject(value, stage + "." + field);
        return new Label(optionalScalarText(value, "id", stage + "." + field),
                optionalScalarText(value, "label", stage + "." + field));
    }

    private Sections sections(JsonNode detail, String stage) {
        JsonNode jobAd = detail.get("jobAd");
        requireObject(jobAd, stage + ".jobAd");
        JsonNode sections = jobAd.get("sections");
        if (sections == null || sections.isNull()) {
            return schemaFailure(stage + ".jobAd.sections", "OBJECT or ARRAY", sections);
        }
        if (sections.isObject()) {
            String base = stage + ".jobAd.sections";
            return new Sections(sectionText(sections.get("companyDescription"), base),
                    sectionText(sections.get("jobDescription"), base),
                    sectionText(sections.get("qualifications"), base),
                    sectionText(sections.get("additionalInformation"), base));
        }
        if (!sections.isArray()) {
            return schemaFailure(stage + ".jobAd.sections", "OBJECT or ARRAY", sections);
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (JsonNode section : sections) {
            requireObject(section, stage + ".jobAd.sections[]");
            String sectionStage = stage + ".jobAd.sections[]";
            String name = firstNonblank(optionalText(section, "identifier", sectionStage),
                    optionalText(section, "name", sectionStage),
                    optionalText(section, "title", sectionStage));
            if (name == null) schemaFailure(sectionStage + " has no identifier, name, or title");
            String text = sectionText(section, sectionStage);
            byName.put(normalizeSectionName(name), text);
        }
        return new Sections(byName.get("companydescription"), byName.get("jobdescription"),
                byName.get("qualifications"), byName.get("additionalinformation"));
    }

    private String sectionText(JsonNode node, String stage) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return node.textValue();
        if (!node.isObject()) return schemaFailure(stage, "STRING or OBJECT", node);
        return optionalText(node, "text", stage);
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

    private int requiredInt(JsonNode node, String field, String stage) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            return schemaFailure(stage + "." + field, "INTEGER", value);
        }
        return value.intValue();
    }

    private String requiredText(JsonNode node, String field, String stage) {
        String value = optionalText(node, field, stage);
        if (value == null || value.isBlank()) {
            return schemaFailure(stage + "." + field, "non-blank STRING", node.get(field));
        }
        return value;
    }

    private String optionalText(JsonNode node, String field, String stage) {
        if (node == null) return schemaFailure(stage, "OBJECT", null);
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (!value.isTextual()) return schemaFailure(stage + "." + field, "STRING", value);
        return value.textValue();
    }

    /**
     * Optional reference value that the provider may serialise as any JSON scalar.
     *
     * <p>SmartRecruiters returns the {@code id} of a reference object such as
     * {@code department} or {@code function} as a string for some companies and as a
     * number for others; both are valid Posting API responses. These values are display
     * and reference text only — they never contribute to the posting's stable identity,
     * its canonical URL, or any eligibility decision — so accepting either scalar form is
     * safe. Objects and arrays are still rejected, and every mandatory field continues to
     * use the strict {@link #requiredText} path.
     */
    private String optionalScalarText(JsonNode node, String field, String stage) {
        if (node == null) return schemaFailure(stage, "OBJECT", null);
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isTextual()) return value.textValue();
        if (value.isNumber() || value.isBoolean()) return value.asText();
        return schemaFailure(stage + "." + field, "STRING, NUMBER, or BOOLEAN", value);
    }

    private void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) schemaFailure(path, "OBJECT", node);
    }

    private Instant parseInstant(String value, String stage) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            // Length only: an ISO-8601 timestamp is not sensitive, but the value is
            // still withheld so the rule, not the data, is what gets persisted.
            return schemaFailure(stage + ".releasedDate is not an ISO-8601 instant (length "
                    + value.length() + ")");
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

    private static <T> T schemaFailure(String path) {
        return schemaFailure(path, null, null);
    }

    /**
     * Rejects a response and names the rule that rejected it. {@code path} and
     * {@code expected} are compile-time literals and {@code actual} contributes only a
     * JSON type name, so the resulting detail can never carry a field value, response
     * fragment, URL, or header.
     */
    private static <T> T schemaFailure(String path, String expected, JsonNode actual) {
        String detail = expected == null ? path
                : path + ": expected " + expected + " but was " + typeName(actual);
        throw new ExternalHttpException(ExternalHttpException.Category.MALFORMED_JSON, null)
                .parseDetail(detail);
    }

    private static String typeName(JsonNode node) {
        return node == null || node.isMissingNode() ? "MISSING" : node.getNodeType().name();
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
