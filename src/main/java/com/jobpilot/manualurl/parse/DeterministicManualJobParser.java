package com.jobpilot.manualurl.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.Hashing;
import com.jobpilot.common.UrlCanonicalizer;
import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.manualurl.domain.ManualSourceClassification;
import com.jobpilot.manualurl.fetch.ManualFetchedResource;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.StreamSupport;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class DeterministicManualJobParser {
    private static final int MIN_DESCRIPTION_LENGTH = 40;
    private static final int MAX_JSON_LD_NODES = 1_000;

    private final ObjectMapper objectMapper;
    private final UrlCanonicalizer canonicalizer;
    private final JobPilotProperties.ManualUrl settings;

    public DeterministicManualJobParser(ObjectMapper objectMapper, UrlCanonicalizer canonicalizer,
                                        JobPilotProperties properties) {
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.settings = properties.manualUrl();
    }

    public ManualParseResult parse(ManualFetchedResource resource) {
        if (resource.contentType().contains("json")) {
            return parseJsonResource(resource);
        }
        Document document = Jsoup.parse(resource.body(), resource.finalUri().toString());
        if (isProtected(document)) {
            return ManualParseResult.failure(ManualParseStatus.BLOCKED_OR_PROTECTED);
        }

        boolean malformedJsonLd = false;
        boolean jobPostingSeen = false;
        for (Element script : document.select("script[type*=ld+json]")) {
            String json = cleanJsonLd(script.data().isBlank() ? script.html() : script.data());
            if (json.isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(json);
                for (JsonNode candidate : topLevelObjects(root)) {
                    if (isJobPosting(candidate)) {
                        jobPostingSeen = true;
                        ParsedManualVacancy parsed = fromJsonLd(candidate, resource.finalUri());
                        if (parsed != null) {
                            return ManualParseResult.success(parsed);
                        }
                    }
                }
            } catch (JsonProcessingException exception) {
                malformedJsonLd = true;
            }
        }

        ParsedManualVacancy metadata = fromMetadata(document, resource.finalUri());
        if (metadata != null) {
            return ManualParseResult.success(metadata);
        }
        if (jobPostingSeen || malformedJsonLd && containsJobPostingMarker(resource.body())) {
            return ManualParseResult.failure(ManualParseStatus.PARSE_FAILED);
        }
        return ManualParseResult.failure(vacancySignal(document)
                ? ManualParseStatus.PARSE_FAILED : ManualParseStatus.UNSUPPORTED_SOURCE);
    }

    private ManualParseResult parseJsonResource(ManualFetchedResource resource) {
        try {
            JsonNode root = objectMapper.readTree(cleanJsonLd(resource.body()));
            boolean jobPostingSeen = false;
            for (JsonNode candidate : topLevelObjects(root)) {
                if (isJobPosting(candidate)) {
                    jobPostingSeen = true;
                    ParsedManualVacancy parsed = fromJsonLd(candidate, resource.finalUri());
                    if (parsed != null) {
                        return ManualParseResult.success(parsed);
                    }
                }
            }
            return ManualParseResult.failure(jobPostingSeen
                    ? ManualParseStatus.PARSE_FAILED : ManualParseStatus.UNSUPPORTED_SOURCE);
        } catch (JsonProcessingException exception) {
            return ManualParseResult.failure(ManualParseStatus.PARSE_FAILED);
        }
    }

    private ParsedManualVacancy fromJsonLd(JsonNode node, URI baseUri) {
        String title = firstText(node, "title", "name");
        String company = organizationName(node.path("hiringOrganization"));
        String description = readableText(node.path("description").asText(null));
        URI canonical = safeCanonical(node.path("url").asText(null), baseUri);
        if (!requiredFieldsValid(title, company, description, canonical)) {
            return null;
        }
        String location = jobLocation(node);
        String employmentType = joinedText(node.path("employmentType"));
        String externalId = identifier(node.path("identifier"));
        return new ParsedManualVacancy(sourceFor(baseUri), bounded(externalId, 255),
                canonical.toString(), title.strip(), company.strip(), bounded(location, 300),
                description, bounded(employmentType, 80), parseInstant(node.path("datePosted").asText(null)),
                parseInstant(firstText(node, "validThrough", "expirationDate")),
                ManualSourceClassification.SCHEMA_ORG_JOB_POSTING);
    }

    private ParsedManualVacancy fromMetadata(Document document, URI baseUri) {
        if (!vacancySignal(document)) {
            return null;
        }
        String title = firstNonBlank(
                meta(document, "meta[property=og:title]"),
                meta(document, "meta[name=twitter:title]"),
                meta(document, "meta[name=job:title]"),
                text(document.selectFirst("h1")), document.title());
        String company = firstNonBlank(
                meta(document, "meta[name=job:company]"),
                meta(document, "meta[property=job:company]"),
                contentOrText(document.selectFirst("[itemprop=hiringOrganization] [itemprop=name]")),
                attribute(document.selectFirst("[data-company]"), "data-company"),
                meta(document, "meta[property=og:site_name]"));
        String description = readableText(firstNonBlank(
                html(document.selectFirst("[itemprop=description]")),
                html(document.selectFirst("[data-job-description]")),
                html(document.selectFirst(".job-description")),
                html(document.selectFirst("main")), html(document.selectFirst("article")),
                meta(document, "meta[property=og:description]"),
                meta(document, "meta[name=description]")));
        URI canonical = safeCanonical(attribute(document.selectFirst("link[rel=canonical]"), "href"), baseUri);
        if (!requiredFieldsValid(title, company, description, canonical)) {
            return null;
        }
        String location = firstNonBlank(
                meta(document, "meta[name=job:location]"),
                contentOrText(document.selectFirst("[itemprop=jobLocation]")),
                attribute(document.selectFirst("[data-job-location]"), "data-job-location"));
        String employmentType = firstNonBlank(
                meta(document, "meta[name=job:employment-type]"),
                contentOrText(document.selectFirst("[itemprop=employmentType]")));
        String externalId = firstNonBlank(
                meta(document, "meta[name=job:id]"),
                attribute(document.selectFirst("[data-job-id]"), "data-job-id"));
        String published = firstNonBlank(meta(document, "meta[name=job:published-date]"),
                attribute(document.selectFirst("time[itemprop=datePosted]"), "datetime"));
        String deadline = firstNonBlank(meta(document, "meta[name=job:deadline]"),
                attribute(document.selectFirst("time[itemprop=validThrough]"), "datetime"));
        return new ParsedManualVacancy(sourceFor(baseUri), bounded(externalId, 255),
                canonical.toString(), title.strip(), company.strip(), bounded(location, 300),
                description, bounded(employmentType, 80), parseInstant(published), parseInstant(deadline),
                ManualSourceClassification.SUPPORTED_HTML_METADATA);
    }

    private List<JsonNode> topLevelObjects(JsonNode root) {
        List<JsonNode> objects = new ArrayList<>();
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty() && objects.size() < MAX_JSON_LD_NODES) {
            JsonNode current = pending.removeFirst();
            if (current.isArray()) {
                current.forEach(pending::addLast);
            } else if (current.isObject()) {
                objects.add(current);
                JsonNode graph = current.path("@graph");
                if (graph.isArray()) {
                    graph.forEach(pending::addLast);
                }
            }
        }
        return objects;
    }

    private boolean isJobPosting(JsonNode node) {
        JsonNode type = node.path("@type");
        if (type.isTextual()) {
            return type.asText().equalsIgnoreCase("JobPosting");
        }
        return type.isArray() && StreamSupport.stream(type.spliterator(), false)
                .anyMatch(value -> value.asText().equalsIgnoreCase("JobPosting"));
    }

    private String organizationName(JsonNode organization) {
        return organization.isTextual() ? organization.asText() : organization.path("name").asText(null);
    }

    private String jobLocation(JsonNode node) {
        List<String> locations = new ArrayList<>();
        JsonNode jobLocation = node.path("jobLocation");
        if (jobLocation.isArray()) {
            jobLocation.forEach(value -> addLocation(locations, value));
        } else {
            addLocation(locations, jobLocation);
        }
        String location = String.join("; ", locations);
        if (node.path("jobLocationType").asText("").toUpperCase(Locale.ROOT).contains("TELECOMMUTE")) {
            location = location.isBlank() ? "Remote" : location + "; Remote";
        }
        return location;
    }

    private void addLocation(List<String> locations, JsonNode location) {
        if (location.isMissingNode() || location.isNull()) {
            return;
        }
        if (location.isTextual()) {
            addNonBlank(locations, location.asText());
            return;
        }
        JsonNode address = location.path("address");
        if (address.isTextual()) {
            addNonBlank(locations, address.asText());
            return;
        }
        List<String> parts = new ArrayList<>();
        addNonBlank(parts, address.path("addressLocality").asText(null));
        addNonBlank(parts, address.path("addressRegion").asText(null));
        JsonNode country = address.path("addressCountry");
        addNonBlank(parts, country.isTextual() ? country.asText() : country.path("name").asText(null));
        addNonBlank(locations, String.join(", ", parts));
    }

    private String joinedText(JsonNode value) {
        if (value.isArray()) {
            return StreamSupport.stream(value.spliterator(), false)
                    .map(JsonNode::asText).filter(text -> !text.isBlank()).distinct()
                    .reduce((left, right) -> left + ", " + right).orElse(null);
        }
        return value.isValueNode() ? value.asText(null) : null;
    }

    private String identifier(JsonNode identifier) {
        if (identifier.isTextual() || identifier.isNumber()) {
            return identifier.asText();
        }
        return firstNonBlank(identifier.path("value").asText(null),
                identifier.path("name").asText(null));
    }

    private boolean requiredFieldsValid(String title, String company, String description, URI canonical) {
        return !isBlank(title) && title.length() <= settings.maxTitleLength()
                && !isBlank(company) && company.length() <= 300
                && !isBlank(description) && description.length() >= MIN_DESCRIPTION_LENGTH
                && description.length() <= settings.maxDescriptionLength()
                && canonical != null && canonical.toString().length() <= 2_000;
    }

    private URI safeCanonical(String candidate, URI baseUri) {
        if (isBlank(candidate)) {
            return canonicalizer.canonicalize(baseUri.toString());
        }
        try {
            URI resolved = baseUri.resolve(candidate);
            URI canonical = canonicalizer.canonicalize(resolved.toString());
            return sameOrigin(baseUri, canonical) ? canonical
                    : canonicalizer.canonicalize(baseUri.toString());
        } catch (IllegalArgumentException exception) {
            return canonicalizer.canonicalize(baseUri.toString());
        }
    }

    private boolean sameOrigin(URI first, URI second) {
        return Objects.equals(lower(first.getHost()), lower(second.getHost()))
                && effectivePort(first) == effectivePort(second)
                && Objects.equals(lower(first.getScheme()), lower(second.getScheme()));
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isProtected(Document document) {
        String title = document.title().toLowerCase(Locale.ROOT);
        String text = document.text();
        String beginning = text.substring(0, Math.min(text.length(), 20_000)).toLowerCase(Locale.ROOT);
        return document.selectFirst("input[type=password], form[action*=login], [id*=captcha], [class*=captcha]") != null
                || containsAny(title + " " + beginning, "verify you are human", "browser verification",
                "checking your browser", "access denied", "captcha", "sign in to continue",
                "login required", "cf-chl-", "challenge-platform");
    }

    private boolean vacancySignal(Document document) {
        if (document.selectFirst("[itemtype*=JobPosting], [data-job-description], .job-description, "
                + "meta[name^=job\\:]") != null) {
            return true;
        }
        String text = document.text().toLowerCase(Locale.ROOT);
        return containsAny(text, "job description", "employment type", "apply for this job",
                "apply for this position", "responsibilities", "qualifications")
                && containsAny(text, "apply", "vacancy", "position", "role", "job");
    }

    private boolean containsJobPostingMarker(String body) {
        return body.toLowerCase(Locale.ROOT).contains("jobposting");
    }

    private String sourceFor(URI uri) {
        String host = lower(uri.getHost());
        if (host != null && (host.equals("greenhouse.io") || host.endsWith(".greenhouse.io"))) {
            return "greenhouse";
        }
        if (host != null && (host.equals("lever.co") || host.endsWith(".lever.co"))) {
            return "lever";
        }
        if (host == null) {
            return "manual";
        }
        String scoped = "manual:" + host;
        return scoped.length() <= 100 ? scoped : "manual:" + Hashing.sha256(host);
    }

    private Instant parseInstant(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredOffset) {
                try {
                    return LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC);
                } catch (DateTimeParseException ignoredDate) {
                    return null;
                }
            }
        }
    }

    private String readableText(String html) {
        if (isBlank(html)) {
            return null;
        }
        return HtmlUtils.htmlUnescape(Jsoup.parseBodyFragment(HtmlUtils.htmlUnescape(html)).text())
                .replaceAll("\\s+", " ").strip();
    }

    private String cleanJsonLd(String value) {
        return HtmlUtils.htmlUnescape(String.valueOf(value))
                .replaceFirst("^\\s*<!--", "").replaceFirst("-->\\s*$", "").strip();
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText(null);
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String meta(Document document, String selector) {
        return attribute(document.selectFirst(selector), "content");
    }

    private String contentOrText(Element element) {
        return firstNonBlank(attribute(element, "content"), text(element));
    }

    private String attribute(Element element, String attribute) {
        return element == null ? null : element.attr(attribute);
    }

    private String text(Element element) {
        return element == null ? null : element.text();
    }

    private String html(Element element) {
        return element == null ? null : element.html();
    }

    private String bounded(String value, int maxLength) {
        return isBlank(value) || value.length() > maxLength ? null : value.strip();
    }

    private void addNonBlank(List<String> values, String value) {
        if (!isBlank(value)) {
            values.add(value.strip());
        }
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
