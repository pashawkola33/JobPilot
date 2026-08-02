package com.jobpilot.jobs.service;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.LocationEligibility;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.jobs.domain.RemoteScope;
import com.jobpilot.jobs.domain.WorkplaceType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Applies the same conservative geographic policy to jobs from every provider. */
@Service
public class LocationEligibilityService {
    private static final Pattern COUNTRY_RESIDENCE = Pattern.compile(
            "(?i)(?:must|should|required to|candidates? must|applicants? must)?\\s*"
                    + "(?:be based|be located|live|reside|be resident|have permanent residence|"
                    + "have (?:valid )?work authori[sz]ation|have the right to work)\\s+(?:in|within)\\s+"
                    + "(?:the\\s+)?(united states|u\\.?s\\.?a?\\.?|canada|united kingdom|u\\.?k\\.?|"
                    + "germany|france|poland|spain|italy|netherlands|australia|india)");
    private static final Pattern COUNTRY_ONLY = Pattern.compile(
            "(?i)\\b(united states|u\\.?s\\.?a?\\.?|canada|united kingdom|u\\.?k\\.?|germany|"
                    + "france|poland|spain|italy|netherlands|australia|india)\\s*(?:residents?\\s*)?only\\b");
    private static final Pattern REMOTE_WITHIN_COUNTRY = Pattern.compile(
            "(?i)\\bremote(?:\\s*[-–—]\\s*|\\s+(?:only\\s+)?(?:within|from|in)\\s+(?:the\\s+)?)"
                    + "(united states|u\\.?s\\.?a?\\.?|canada|united kingdom|u\\.?k\\.?|germany|"
                    + "france|poland|spain|italy|netherlands|australia|india)\\b");
    private static final Pattern OFFICE_ATTENDANCE = Pattern.compile(
            "(?i)(?:occasional|regular|weekly|monthly|quarterly|required|must|need to)[^.!?]{0,80}"
                    + "(?:office|on[- ]?site|onsite|commuting distance|commute)");
    private static final Pattern TIMEZONE_VALUE = Pattern.compile(
            "(?i)\\b(?:PST|PDT|EST|EDT|CST|MST|Pacific(?: Time)?|Eastern(?: Time)?|"
                    + "UTC\\s*[-−]\\s*(?:4|5|6|7|8|9|10|11|12))\\b");
    private static final List<String> REMOTE_NOISE = List.of(
            "remote interview", "remote interviews", "remote onboarding", "remote collaboration",
            "remote support", "remote team member", "remote team members", "work remotely occasionally",
            "ability to work remotely occasionally");
    private static final Set<String> NON_ROMANIA_COUNTRIES = Set.of(
            "united states", "usa", "us", "canada", "united kingdom", "uk", "germany",
            "france", "poland", "spain", "italy", "netherlands", "australia", "india");

    private final JobPilotProperties.Eligibility settings;

    @Autowired
    public LocationEligibilityService(JobPilotProperties properties) {
        this(properties.eligibility());
    }

    public LocationEligibilityService(JobPilotProperties.Eligibility settings) {
        this.settings = settings == null ? JobPilotProperties.Eligibility.defaults() : settings;
    }

    public LocationEligibilityDecision evaluate(RawJob raw) {
        if (raw == null) throw new IllegalArgumentException("Raw job is required");
        RawLocationData data = raw.locationData();
        List<String> structuredLocations = new ArrayList<>(data.structuredLocations());
        addNonBlank(structuredLocations, raw.location());
        String locations = String.join(" | ", structuredLocations);
        String description = safe(raw.description());

        WorkplaceType providerType = workplace(data.workplaceType());
        WorkplaceType structuredLocationType = workplace(locations);
        WorkplaceType jsonLdType = workplace(data.jobLocationType());
        WorkplaceType descriptionType = descriptionWorkplace(description);
        WorkplaceType workplace = firstKnown(providerType, structuredLocationType, jsonLdType, descriptionType);
        if (workplace == WorkplaceType.UNKNOWN && !locations.isBlank()
                && !onlyRemoteNoise(locations)) {
            workplace = WorkplaceType.ONSITE;
        }

        boolean temporaryRemote = containsAny(normalize(description),
                "remote until further notice", "temporarily remote", "remote temporarily");
        boolean officeAttendance = OFFICE_ATTENDANCE.matcher(description).find()
                || containsAny(normalize(description), "within commuting distance", "must commute");
        boolean structuredContradiction = providerType != WorkplaceType.UNKNOWN
                && ((jsonLdType != WorkplaceType.UNKNOWN && jsonLdType != providerType)
                || (descriptionType != WorkplaceType.UNKNOWN && descriptionType != providerType));

        String normalizedLocations = normalize(locations);
        String normalizedDescription = normalize(description);
        boolean structuredBucharest = containsBucharest(normalizedLocations);
        boolean descriptionBucharest = explicitBucharestDescription(normalizedDescription);
        boolean bucharest = structuredBucharest || descriptionBucharest;
        boolean ilfov = word(normalizedLocations, "ilfov");
        String city = normalizedCity(normalizedLocations);
        String country = normalizedCountry(normalizedLocations);
        List<String> restrictions = restrictions(raw, officeAttendance);
        String requiredTimezone = firstNonBlank(data.requiredTimezone(), detectedTimezone(description));
        String requiredAuthorization = firstNonBlank(data.requiredWorkAuthorization(),
                detectedWorkAuthorization(description));

        if (descriptionBucharest && !structuredBucharest
                && (city != null && !"Bucharest".equals(city)
                || country != null && !settings.targetCountry().equalsIgnoreCase(country))) {
            return unknown(workplace, city, country,
                    "Structured location contradicts the description", restrictions,
                    requiredTimezone, requiredAuthorization);
        }

        if (workplace == WorkplaceType.REMOTE && officeAttendance && !bucharest) {
            String outside = city == null ? "outside Bucharest" : "in " + city;
            return rejected(workplace, RemoteScope.REGION_RESTRICTED, city, country,
                    "Remote role requires office attendance " + outside, restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        if (structuredContradiction) {
            return unknown(workplace, city, country,
                    "Provider workplace data contradicts the description", restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        if (temporaryRemote) {
            return unknown(WorkplaceType.REMOTE, city, country,
                    "Remote arrangement is temporary", restrictions,
                    requiredTimezone, requiredAuthorization);
        }

        if (bucharest || ilfov && settings.includeIlfov()) {
            return localDecision(workplace, city, country, restrictions,
                    requiredTimezone, requiredAuthorization, ilfov && !bucharest);
        }

        if (workplace == WorkplaceType.ONSITE || workplace == WorkplaceType.HYBRID) {
            String place = city != null ? city : country != null ? country : "outside Bucharest";
            return rejected(workplace, RemoteScope.UNKNOWN, city, country,
                    title(workplace.name()) + " role located in " + place, restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        if (workplace == WorkplaceType.UNKNOWN) {
            return unknown(workplace, city, country,
                    remoteNoisePresent(description) ? "Remote employment was not established"
                            : "Workplace type is missing",
                    restrictions, requiredTimezone, requiredAuthorization);
        }

        RemoteScope scope = remoteScope(raw);
        String countryRestriction = countryRestriction(raw);
        if (countryRestriction != null) {
            restrictions = append(restrictions, countryRestriction);
            return rejected(workplace, RemoteScope.COUNTRY_RESTRICTED, city, country,
                    "Remote role restricted to " + countryRestriction + " residents", restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        String regionRestriction = regionRestriction(raw);
        if (regionRestriction != null) {
            restrictions = append(restrictions, regionRestriction);
            return rejected(workplace, RemoteScope.REGION_RESTRICTED, city, country,
                    "Remote role restricted to " + regionRestriction, restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        if (incompatibleTimezone(requiredTimezone)) {
            restrictions = append(restrictions, "Timezone: " + requiredTimezone);
            return rejected(workplace, RemoteScope.REGION_RESTRICTED, city, country,
                    "Required timezone is incompatible with Romania", restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        String authorizationCountry = authorizationCountry(requiredAuthorization);
        if (authorizationCountry != null) {
            restrictions = append(restrictions, "Work authorization: " + authorizationCountry);
            return rejected(workplace, RemoteScope.COUNTRY_RESTRICTED, city, country,
                    "Work authorization required in " + authorizationCountry, restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        if (scope == RemoteScope.UNKNOWN) {
            return unknown(workplace, city, country,
                    "Remote role found, but permitted countries were not specified", restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        if (!settings.acceptRemoteFromRomania() || !settings.acceptedRemoteRegions().contains(scope)) {
            return rejected(workplace, scope, city, country,
                    "Remote scope " + scope + " is disabled by configuration", restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        return new LocationEligibilityDecision(workplace,
                LocationEligibility.REMOTE_ROMANIA_ELIGIBLE, scope, city,
                country == null ? settings.targetCountry() : country, true,
                "Fully remote role open to " + scopeLabel(scope), restrictions,
                requiredTimezone, requiredAuthorization);
    }

    private LocationEligibilityDecision localDecision(
            WorkplaceType workplace, String city, String country, List<String> restrictions,
            String timezone, String authorization, boolean includedIlfov) {
        boolean accepted = switch (workplace) {
            case ONSITE -> settings.acceptBucharestOnsite();
            case HYBRID -> settings.acceptBucharestHybrid();
            case REMOTE -> settings.acceptBucharestRemote();
            case UNKNOWN -> false;
        };
        String localCity = includedIlfov ? "Ilfov" : settings.targetCity();
        if (!accepted) {
            String reason = workplace == WorkplaceType.UNKNOWN
                    ? "Workplace type is missing"
                    : title(workplace.name()) + " roles in " + localCity + " are disabled by configuration";
            return workplace == WorkplaceType.UNKNOWN
                    ? unknown(workplace, localCity, settings.targetCountry(), reason, restrictions,
                    timezone, authorization)
                    : rejected(workplace, RemoteScope.UNKNOWN, localCity, settings.targetCountry(),
                    reason, restrictions, timezone, authorization);
        }
        return new LocationEligibilityDecision(workplace, LocationEligibility.BUCHAREST_LOCAL,
                workplace == WorkplaceType.REMOTE ? RemoteScope.ROMANIA : RemoteScope.UNKNOWN,
                localCity, settings.targetCountry(), true,
                includedIlfov ? "Explicit Ilfov location enabled by configuration"
                        : "Explicit Bucharest location",
                restrictions, timezone, authorization);
    }

    private LocationEligibilityDecision rejected(
            WorkplaceType workplace, RemoteScope scope, String city, String country, String reason,
            List<String> restrictions, String timezone, String authorization) {
        return new LocationEligibilityDecision(workplace, LocationEligibility.REJECTED_LOCATION,
                scope, city, country, false, reason, restrictions, timezone, authorization);
    }

    private LocationEligibilityDecision unknown(
            WorkplaceType workplace, String city, String country, String reason,
            List<String> restrictions, String timezone, String authorization) {
        return new LocationEligibilityDecision(workplace,
                LocationEligibility.REMOTE_ELIGIBILITY_UNKNOWN, RemoteScope.UNKNOWN,
                city, country, false, reason, restrictions, timezone, authorization);
    }

    private RemoteScope remoteScope(RawJob raw) {
        RawLocationData data = raw.locationData();
        List<String> structured = new ArrayList<>();
        structured.addAll(data.applicantLocationRequirements());
        structured.addAll(data.remoteRegions());
        structured.addAll(data.structuredLocations());
        addNonBlank(structured, raw.location());
        for (String candidate : structured) {
            RemoteScope detected = scope(candidate, true);
            if (detected != RemoteScope.UNKNOWN) return detected;
        }
        return scope(raw.description(), false);
    }

    private RemoteScope scope(String candidate, boolean structured) {
        String value = normalize(candidate);
        if (!structured) {
            if (containsAny(value, "remote romania", "romania remote", "work from romania",
                    "based in romania", "located in romania", "candidates in romania",
                    "applicants in romania")) return RemoteScope.ROMANIA;
            if (containsAny(value, "remote emea", "remote within emea", "open to emea",
                    "based in emea", "across emea", "europe middle east and africa")) {
                return RemoteScope.EMEA;
            }
            if (containsAny(value, "remote eea", "remote within eea", "open to eea",
                    "based in eea", "european economic area")) return RemoteScope.EEA;
            if (containsAny(value, "remote eu", "remote within the eu", "open to the eu",
                    "based in the eu", "european union")) return RemoteScope.EU;
            if (containsAny(value, "remote europe", "remote within europe", "remote across europe",
                    "open to europe", "based in europe", "located in europe",
                    "europe timezone", "european timezone")) return RemoteScope.EUROPE;
            if (containsAny(value, "worldwide remote", "remote worldwide", "global remote",
                    "remote globally", "work from anywhere", "anywhere in the world")) {
                return RemoteScope.WORLDWIDE;
            }
            return RemoteScope.UNKNOWN;
        }
        if (word(value, "romania") || word(value, "romanian")) return RemoteScope.ROMANIA;
        if (word(value, "emea") || value.contains("europe middle east and africa")) return RemoteScope.EMEA;
        if (word(value, "eea") || value.contains("european economic area")) return RemoteScope.EEA;
        if (word(value, "eu") || value.contains("european union")) return RemoteScope.EU;
        if (word(value, "europe") || word(value, "european")) return RemoteScope.EUROPE;
        if (containsAny(value, "worldwide remote", "remote worldwide", "global remote",
                "remote globally", "work from anywhere", "anywhere in the world")
                || word(value, "worldwide") || word(value, "global")
                || standaloneAnywhere(value)) return RemoteScope.WORLDWIDE;
        return RemoteScope.UNKNOWN;
    }

    private String countryRestriction(RawJob raw) {
        String text = restrictionText(raw);
        for (Pattern pattern : List.of(COUNTRY_ONLY, REMOTE_WITHIN_COUNTRY, COUNTRY_RESIDENCE)) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) return displayCountry(matcher.group(1));
        }
        List<String> structuredRestrictions = new ArrayList<>();
        structuredRestrictions.addAll(raw.locationData().applicantLocationRequirements());
        structuredRestrictions.addAll(raw.locationData().remoteRegions());
        addNonBlank(structuredRestrictions, raw.location());
        for (String value : structuredRestrictions) {
            String normalized = normalize(value);
            if (word(normalized, "romania") || word(normalized, "europe")
                    || word(normalized, "eu") || word(normalized, "eea") || word(normalized, "emea")) {
                continue;
            }
            for (String country : NON_ROMANIA_COUNTRIES) {
                if (normalized.equals(country) || normalized.equals("remote " + country)) {
                    return displayCountry(country);
                }
            }
        }
        return null;
    }

    private String regionRestriction(RawJob raw) {
        String text = normalize(restrictionText(raw));
        if (containsAny(text, "apac only", "asia pacific only", "remote within apac",
                "must be based in apac")) return "APAC";
        if (containsAny(text, "americas only", "north america only", "latin america only",
                "remote within the americas", "must be based in the americas")) return "Americas";
        return null;
    }

    private List<String> restrictions(RawJob raw, boolean officeAttendance) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(raw.locationData().applicantLocationRequirements());
        if (officeAttendance) values.add("Office attendance required");
        String country = countryRestriction(raw);
        if (country != null) values.add(country);
        String region = regionRestriction(raw);
        if (region != null) values.add(region);
        return List.copyOf(values);
    }

    private String restrictionText(RawJob raw) {
        RawLocationData data = raw.locationData();
        return String.join(" | ", String.join(" | ", data.applicantLocationRequirements()),
                String.join(" | ", data.remoteRegions()), safe(data.requiredWorkAuthorization()),
                safe(data.requiredTimezone()), safe(raw.location()), safe(raw.description()));
    }

    private WorkplaceType workplace(String value) {
        String text = normalize(value);
        if (text.isBlank()) return WorkplaceType.UNKNOWN;
        if (containsAny(text, "hybrid", "remote until", "partly remote", "partially remote")) {
            return WorkplaceType.HYBRID;
        }
        if (containsAny(text, "telecommute", "fully remote", "100 remote", "work from anywhere",
                "worldwide remote", "remote europe", "remote eu", "remote emea", "romania remote")
                || word(text, "remote") || containsAny(text, "home based", "work from home")) {
            return WorkplaceType.REMOTE;
        }
        if (containsAny(text, "on site", "onsite", "office based", "in office")) {
            return WorkplaceType.ONSITE;
        }
        return WorkplaceType.UNKNOWN;
    }

    private WorkplaceType descriptionWorkplace(String description) {
        String text = normalize(description);
        for (String noise : REMOTE_NOISE) text = text.replace(noise, " ");
        if (containsAny(text, "hybrid role", "hybrid position", "hybrid working", "hybrid work")) {
            return WorkplaceType.HYBRID;
        }
        if (containsAny(text, "fully remote", "100 remote", "work from anywhere",
                "worldwide remote", "remote role", "remote position", "remote job",
                "remote europe", "remote eu", "remote emea", "romania remote",
                "distributed team hiring")) return WorkplaceType.REMOTE;
        if (containsAny(text, "onsite role", "on site role", "office based role",
                "work from our office")) return WorkplaceType.ONSITE;
        return WorkplaceType.UNKNOWN;
    }

    private WorkplaceType firstKnown(WorkplaceType... values) {
        for (WorkplaceType value : values) if (value != WorkplaceType.UNKNOWN) return value;
        return WorkplaceType.UNKNOWN;
    }

    private boolean containsBucharest(String text) {
        return word(text, "bucharest") || word(text, "bucuresti")
                || text.contains("bucharest metropolitan area");
    }

    private boolean explicitBucharestDescription(String text) {
        return containsAny(text, "located in bucharest", "based in bucharest", "office in bucharest",
                "bucharest based", "located in bucuresti", "based in bucuresti", "office in bucuresti");
    }

    private String normalizedCity(String locations) {
        if (containsBucharest(locations)) return settings.targetCity();
        if (word(locations, "ilfov")) return "Ilfov";
        for (String city : List.of("Cluj-Napoca", "Berlin", "London", "Paris", "Munich",
                "Warsaw", "Amsterdam", "Toronto", "New York")) {
            if (normalize(locations).contains(normalize(city))) return city;
        }
        return null;
    }

    private String normalizedCountry(String locations) {
        String text = normalize(locations);
        if (word(text, "romania") || word(text, "romania")) return settings.targetCountry();
        for (String country : NON_ROMANIA_COUNTRIES) {
            if (word(text, country)) return displayCountry(country);
        }
        return null;
    }

    private String detectedTimezone(String description) {
        Matcher matcher = TIMEZONE_VALUE.matcher(description);
        return matcher.find() ? matcher.group() : null;
    }

    private String detectedWorkAuthorization(String description) {
        Matcher matcher = Pattern.compile("(?i)(?:work authori[sz]ation|right to work)[^.!?]{0,80}")
                .matcher(description);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private boolean incompatibleTimezone(String timezone) {
        return timezone != null && TIMEZONE_VALUE.matcher(timezone).find();
    }

    private String authorizationCountry(String authorization) {
        String text = normalize(authorization);
        if (text.isBlank() || word(text, "romania") || word(text, "europe")
                || word(text, "eu") || word(text, "emea")) return null;
        for (String country : NON_ROMANIA_COUNTRIES) {
            if (word(text, country)) return displayCountry(country);
        }
        return null;
    }

    private boolean onlyRemoteNoise(String value) {
        String text = normalize(value);
        for (String noise : REMOTE_NOISE) text = text.replace(noise, " ");
        return text.isBlank();
    }

    private boolean remoteNoisePresent(String value) {
        String text = normalize(value);
        return REMOTE_NOISE.stream().anyMatch(text::contains);
    }

    private boolean standaloneAnywhere(String text) {
        return word(text, "anywhere") && !containsAny(text,
                "anywhere in the us", "anywhere in canada", "anywhere in the uk",
                "anywhere in germany", "anywhere in apac", "anywhere in the americas");
    }

    private String scopeLabel(RemoteScope scope) {
        return switch (scope) {
            case ROMANIA -> "Romania";
            case EU -> "EU";
            case EEA -> "EEA";
            case EUROPE -> "Europe";
            case EMEA -> "EMEA";
            case WORLDWIDE -> "worldwide";
            default -> scope.name();
        };
    }

    private String displayCountry(String raw) {
        String value = normalize(raw).replace(".", "");
        return switch (value) {
            case "us", "usa", "united states" -> "United States";
            case "uk", "united kingdom" -> "United Kingdom";
            default -> title(value);
        };
    }

    private String normalize(String value) {
        String decomposed = Normalizer.normalize(safe(value), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+-]+", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private boolean word(String text, String value) {
        String normalizedText = " " + normalize(text) + " ";
        String normalizedValue = " " + normalize(value) + " ";
        return normalizedText.contains(normalizedValue);
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private List<String> append(List<String> values, String value) {
        LinkedHashSet<String> result = new LinkedHashSet<>(values);
        if (value != null && !value.isBlank()) result.add(value);
        return List.copyOf(result);
    }

    private String title(String value) {
        if (value == null || value.isBlank()) return value;
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return null;
    }

    private void addNonBlank(List<String> values, String value) {
        if (value != null && !value.isBlank()) values.add(value.strip());
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
