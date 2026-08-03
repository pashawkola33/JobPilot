package com.jobpilot.jobs.service;

import com.jobpilot.config.JobPilotProperties;
import com.jobpilot.jobs.domain.LocationEligibility;
import com.jobpilot.jobs.domain.LocationEligibilityDecision;
import com.jobpilot.jobs.domain.RawJob;
import com.jobpilot.jobs.domain.RawLocationData;
import com.jobpilot.jobs.domain.RemoteScope;
import com.jobpilot.jobs.domain.ScreeningDisposition;
import com.jobpilot.jobs.domain.ScreeningReason;
import com.jobpilot.jobs.domain.ScreeningStage;
import com.jobpilot.jobs.domain.WorkplaceType;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
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
    private static final String US_STATE_CODES =
            "AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|"
                    + "MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|"
                    + "UT|VT|VA|WA|WV|WI|WY";
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
    private static final Pattern WORK_AUTHORIZATION = Pattern.compile(
            "(?i)(?:work authori[sz]ation|right to work)[^.!?]{0,80}");
    private static final Pattern US_STATE_CODE = Pattern.compile(
            "(?i)(?:[,|/()]|\\s[-–—]\\s)\\s*(?:" + US_STATE_CODES + ")(?:\\b|\\))");
    private static final Pattern TITLE_PARENTHESIZED_LOCATION = Pattern.compile(
            "\\(([^()]{2,80})\\)\\s*$");
    private static final Pattern TITLE_DASH_LOCATION = Pattern.compile(
            "\\s[-–—]\\s*([^|()]{2,80})\\s*$");
    private static final Pattern TITLE_US_CITY_STATE = Pattern.compile(
            "(?iu)([\\p{L}][\\p{L}.'-]*(?:\\s+[\\p{L}][\\p{L}.'-]*){0,3}"
                    + "\\s*,\\s*(?:" + US_STATE_CODES + "))\\s*$");
    private static final Pattern US_CITY_STATE = Pattern.compile(
            "(?iu)[\\p{L}][\\p{L}.'-]*(?:\\s+[\\p{L}][\\p{L}.'-]*){0,3}"
                    + "\\s*,\\s*(?:" + US_STATE_CODES + ")");
    private static final List<String> REMOTE_NOISE = List.of(
            "remote interview", "remote interviews", "remote onboarding", "remote collaboration",
            "remote support", "remote team member", "remote team members", "work remotely occasionally",
            "ability to work remotely occasionally");
    private static final Set<String> NON_ROMANIA_COUNTRIES = nonRomaniaCountries();
    private static final List<String> INCOMPATIBLE_CITIES = List.of(
            "San Francisco", "Seattle", "Boston", "Austin", "New York", "Los Angeles",
            "Chicago", "Denver", "Portland", "Washington DC", "London", "Paris", "Berlin",
            "Munich", "Warsaw", "Amsterdam", "Toronto", "Vancouver");
    private static final List<String> US_STATES = List.of(
            "Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado", "Connecticut",
            "Delaware", "Florida", "Georgia", "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa",
            "Kansas", "Kentucky", "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan",
            "Minnesota", "Mississippi", "Missouri", "Montana", "Nebraska", "Nevada",
            "New Hampshire", "New Jersey", "New Mexico", "New York", "North Carolina",
            "North Dakota", "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island",
            "South Carolina", "South Dakota", "Tennessee", "Texas", "Utah", "Vermont",
            "Virginia", "Washington", "West Virginia", "Wisconsin", "Wyoming");

    /**
     * Country restriction phrases, pre-normalized once at class initialisation. The list
     * preserves the exact iteration order of {@link #NON_ROMANIA_COUNTRIES} so first-match
     * wins identically to the previous nested loop.
     */
    private static final List<CountryPhrases> COUNTRY_PHRASES = countryPhrases();
    /** Pre-normalized vocabulary, so no fixed term is normalized per vacancy. */
    private static final List<String> NORMALIZED_INCOMPATIBLE_CITIES =
            normalizedAll(INCOMPATIBLE_CITIES);
    private static final List<String> NORMALIZED_US_STATES = normalizedAll(US_STATES);
    private static final List<String> NORMALIZED_COUNTRY_TERMS =
            normalizedAll(List.copyOf(NON_ROMANIA_COUNTRIES));
    private static final List<String> COUNTRY_TERMS = List.copyOf(NON_ROMANIA_COUNTRIES);
    private static final List<String> NORMALIZED_CITY_LABELS = normalizedAll(List.of(
            "Cluj-Napoca", "San Francisco", "Seattle", "Boston", "Austin",
            "Berlin", "London", "Paris", "Munich", "Warsaw", "Amsterdam", "Toronto",
            "New York"));
    private static final List<String> CITY_LABELS = List.of(
            "Cluj-Napoca", "San Francisco", "Seattle", "Boston", "Austin",
            "Berlin", "London", "Paris", "Munich", "Warsaw", "Amsterdam", "Toronto",
            "New York");

    private final JobPilotProperties.Eligibility settings;
    private final LocationTextNormalizer normalizer;

    @Autowired
    public LocationEligibilityService(JobPilotProperties properties) {
        this(properties.eligibility());
    }

    public LocationEligibilityService(JobPilotProperties.Eligibility settings) {
        this(settings, LocationTextNormalizer.standard());
    }

    /** Test seam: lets a counting normalizer prove the bounded call count. */
    LocationEligibilityService(JobPilotProperties.Eligibility settings,
                               LocationTextNormalizer normalizer) {
        this.settings = settings == null ? JobPilotProperties.Eligibility.defaults() : settings;
        this.normalizer = normalizer == null ? LocationTextNormalizer.standard() : normalizer;
    }

    private static List<String> normalizedAll(List<String> values) {
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) result.add(LocationTextNormalizer.standard().normalize(value));
        return List.copyOf(result);
    }

    /** The seven fixed restriction templates, pre-built per country. */
    private static List<CountryPhrases> countryPhrases() {
        LocationTextNormalizer base = LocationTextNormalizer.standard();
        List<CountryPhrases> phrases = new ArrayList<>(NON_ROMANIA_COUNTRIES.size());
        for (String country : NON_ROMANIA_COUNTRIES) {
            String normalized = base.normalize(country);
            phrases.add(new CountryPhrases(country,
                    List.of(LocationTextNormalizer.SPACE.split(normalized)), List.of(
                    normalized + " only",
                    normalized + " residents only",
                    "remote in " + normalized,
                    "remote within " + normalized,
                    "must be based in " + normalized,
                    "must be located in " + normalized,
                    "must reside in " + normalized)));
        }
        return List.copyOf(phrases);
    }

    private record CountryPhrases(String country, List<String> countryTokens,
                                  List<String> normalizedPhrases) {
    }

    /**
     * Immutable per-vacancy normalization results.
     *
     * <p>Created once at the top of {@link #evaluate(RawJob)} and threaded into the
     * restriction helpers so no helper re-normalizes a field or rebuilds the concatenated
     * restriction string. Holds no mutable state and is never cached across vacancies.
     */
    private record LocationEvaluationContext(
            String restrictionText,
            LocationTextNormalizer.NormalizedText normalizedRestriction) {
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

        String normalizedDescriptionForFlags = normalize(description);
        boolean temporaryRemote = containsAny(normalizedDescriptionForFlags,
                "remote until further notice", "temporarily remote", "remote temporarily");
        boolean officeAttendance = OFFICE_ATTENDANCE.matcher(description).find()
                || containsAny(normalizedDescriptionForFlags,
                "within commuting distance", "must commute");
        boolean structuredContradiction = providerType != WorkplaceType.UNKNOWN
                && ((jsonLdType != WorkplaceType.UNKNOWN && jsonLdType != providerType)
                || (descriptionType != WorkplaceType.UNKNOWN && descriptionType != providerType));

        // Each field is normalized exactly once here; every downstream check reuses these.
        LocationTextNormalizer.NormalizedText normalizedLocationsText = normalized(locations);
        LocationTextNormalizer.NormalizedText normalizedDescriptionText = normalized(description);
        String normalizedLocations = normalizedLocationsText.value();
        String normalizedDescription = normalizedDescriptionText.value();
        String restrictionText = restrictionText(raw);
        LocationEvaluationContext context = new LocationEvaluationContext(
                restrictionText, normalized(restrictionText));
        boolean structuredBucharest = containsBucharest(normalizedLocationsText);
        boolean descriptionBucharest = explicitBucharestDescription(normalizedDescription);
        boolean bucharest = structuredBucharest || descriptionBucharest;
        boolean ilfov = wordIn(normalizedLocationsText, "ilfov");
        String city = normalizedCity(normalizedLocationsText);
        String country = normalizedCountry(normalizedLocationsText);
        List<String> restrictions = restrictions(raw, officeAttendance, context);
        String requiredTimezone = firstNonBlank(data.requiredTimezone(), detectedTimezone(description));
        String requiredAuthorization = firstNonBlank(data.requiredWorkAuthorization(),
                detectedWorkAuthorization(description));
        String titleLocation = explicitIncompatibleTitleLocation(raw.title());
        String incompatibleLocation = firstNonBlank(
                titleLocation, explicitIncompatibleLocation(locations));
        RemoteScope authoritativeApplicantScope = authoritativeApplicantScope(raw);

        if (descriptionBucharest && !structuredBucharest
                && (city != null && !"Bucharest".equals(city)
                || country != null && !settings.targetCountry().equalsIgnoreCase(country))) {
            return unknown(workplace, city, country,
                    "Structured location contradicts the description", restrictions,
                    requiredTimezone, requiredAuthorization);
        }

        if (incompatibleLocation != null) {
            WorkplaceType explicitWorkplace = explicitLocalWorkplace(
                    structuredLocationType, jsonLdType, descriptionType, workplace);
            boolean titleEvidence = titleLocation != null;
            boolean authoritativeContradiction = acceptedScope(authoritativeApplicantScope)
                    && (titleEvidence || explicitWorkplace == WorkplaceType.REMOTE);
            String evidenceLabel = titleEvidence ? "Title location: " : "Structured location: ";
            String reasonLabel = titleEvidence ? "title location" : "structured location";
            if (authoritativeContradiction) {
                restrictions = append(restrictions, evidenceLabel + incompatibleLocation);
                return unknown(explicitWorkplace, city, country,
                        "Explicit " + reasonLabel + " " + incompatibleLocation
                                + " contradicts authoritative applicant scope "
                                + scopeLabel(authoritativeApplicantScope),
                        restrictions, requiredTimezone, requiredAuthorization,
                        "LOCATION_SCOPE_CONTRADICTION");
            }
            restrictions = append(restrictions, evidenceLabel + incompatibleLocation);
            return rejected(explicitWorkplace, RemoteScope.COUNTRY_RESTRICTED, city, country,
                    "Explicit " + reasonLabel + " is incompatible with Romania: "
                            + incompatibleLocation,
                    restrictions, requiredTimezone, requiredAuthorization);
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
        String countryRestriction = countryRestriction(raw, context);
        if (countryRestriction != null) {
            restrictions = append(restrictions, countryRestriction);
            return rejected(workplace, RemoteScope.COUNTRY_RESTRICTED, city, country,
                    "Remote role restricted to " + countryRestriction + " residents", restrictions,
                    requiredTimezone, requiredAuthorization);
        }
        String regionRestriction = regionRestriction(context);
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
        return unknown(workplace, city, country, reason, restrictions, timezone, authorization,
                "LOCATION_UNCERTAIN");
    }

    private LocationEligibilityDecision unknown(
            WorkplaceType workplace, String city, String country, String reason,
            List<String> restrictions, String timezone, String authorization, String reasonCode) {
        return new LocationEligibilityDecision(workplace,
                LocationEligibility.REMOTE_ELIGIBILITY_UNKNOWN, RemoteScope.UNKNOWN,
                city, country, false, reason, restrictions, timezone, authorization,
                ScreeningDisposition.REVIEW,
                List.of(new ScreeningReason(ScreeningStage.LOCATION, reasonCode, reason)));
    }

    private WorkplaceType explicitLocalWorkplace(
            WorkplaceType structured, WorkplaceType jsonLd, WorkplaceType description,
            WorkplaceType fallback) {
        for (WorkplaceType candidate : List.of(structured, jsonLd, description)) {
            if (candidate == WorkplaceType.ONSITE || candidate == WorkplaceType.HYBRID) {
                return candidate;
            }
        }
        return fallback;
    }

    private RemoteScope authoritativeApplicantScope(RawJob raw) {
        RawLocationData data = raw.locationData();
        for (String value : data.applicantLocationRequirements()) {
            RemoteScope detected = scope(value, true);
            if (detected != RemoteScope.UNKNOWN) return detected;
        }
        for (String value : data.remoteRegions()) {
            RemoteScope detected = scope(value, true);
            if (detected != RemoteScope.UNKNOWN) return detected;
        }
        for (String value : data.structuredLocations()) {
            RemoteScope detected = scope(value, true);
            if (detected != RemoteScope.UNKNOWN) return detected;
        }
        RemoteScope locationScope = scope(raw.location(), true);
        if (locationScope != RemoteScope.UNKNOWN) return locationScope;
        return RemoteScope.UNKNOWN;
    }

    private boolean acceptedScope(RemoteScope scope) {
        return scope != RemoteScope.UNKNOWN && settings.acceptRemoteFromRomania()
                && settings.acceptedRemoteRegions().contains(scope);
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
        LocationTextNormalizer.NormalizedText normalizedCandidate = normalized(candidate);
        String value = normalizedCandidate.value();
        if (containsAny(value, "timezone", "time zone")
                && !containsAny(value, "open to", "applicants", "candidates", "hiring in",
                "based in", "located in")) {
            return RemoteScope.UNKNOWN;
        }
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
                    "open to europe", "based in europe", "located in europe")) {
                return RemoteScope.EUROPE;
            }
            if (containsAny(value, "worldwide remote", "remote worldwide", "global remote",
                    "remote globally", "work from anywhere", "anywhere in the world")) {
                return RemoteScope.WORLDWIDE;
            }
            return RemoteScope.UNKNOWN;
        }
        if (normalizedCandidate.containsPhrase("romania")
                || normalizedCandidate.containsPhrase("romanian")) return RemoteScope.ROMANIA;
        if (normalizedCandidate.containsPhrase("emea")
                || value.contains("europe middle east and africa")) return RemoteScope.EMEA;
        if (normalizedCandidate.containsPhrase("eea")
                || value.contains("european economic area")) return RemoteScope.EEA;
        if (normalizedCandidate.containsPhrase("eu")
                || value.contains("european union")) return RemoteScope.EU;
        if (normalizedCandidate.containsPhrase("europe")
                || normalizedCandidate.containsPhrase("european")) return RemoteScope.EUROPE;
        if (containsAny(value, "worldwide remote", "remote worldwide", "global remote",
                "remote globally", "work from anywhere", "anywhere in the world")
                || normalizedCandidate.containsPhrase("worldwide")
                || normalizedCandidate.containsPhrase("global")
                || standaloneAnywhere(normalizedCandidate)) return RemoteScope.WORLDWIDE;
        return RemoteScope.UNKNOWN;
    }

    private String explicitIncompatibleLocation(String locations) {
        LocationTextNormalizer.NormalizedText text = normalized(locations);
        if (text.isBlank()) return null;
        for (int index = 0; index < INCOMPATIBLE_CITIES.size(); index++) {
            if (text.containsPhrase(NORMALIZED_INCOMPATIBLE_CITIES.get(index))) {
                return INCOMPATIBLE_CITIES.get(index);
            }
        }
        for (int index = 0; index < US_STATES.size(); index++) {
            if (text.containsPhrase(NORMALIZED_US_STATES.get(index))) {
                return US_STATES.get(index) + ", United States";
            }
        }
        if (US_STATE_CODE.matcher(locations).find()) return "United States";
        for (int index = 0; index < COUNTRY_TERMS.size(); index++) {
            if (text.containsPhrase(NORMALIZED_COUNTRY_TERMS.get(index))) {
                return displayCountry(COUNTRY_TERMS.get(index));
            }
        }
        return null;
    }

    private String explicitIncompatibleTitleLocation(String title) {
        String value = safe(title).strip();
        if (value.isBlank()) return null;

        for (Pattern structuredSuffix : List.of(
                TITLE_PARENTHESIZED_LOCATION, TITLE_DASH_LOCATION)) {
            Matcher matcher = structuredSuffix.matcher(value);
            if (matcher.find()) {
                String location = incompatibleTitleLocationCandidate(matcher.group(1));
                if (location != null) return location;
            }
        }

        Matcher cityState = TITLE_US_CITY_STATE.matcher(value);
        if (cityState.find()) return cityState.group(1).strip();

        int comma = value.lastIndexOf(',');
        if (comma > 0 && comma < value.length() - 1) {
            String suffix = value.substring(comma + 1).strip();
            String country = exactIncompatibleCountry(suffix);
            if (country != null) return country;
            String normalizedSuffix = normalize(suffix);
            for (int index = 0; index < US_STATES.size(); index++) {
                if (normalizedSuffix.equals(NORMALIZED_US_STATES.get(index))) {
                    return US_STATES.get(index) + ", United States";
                }
            }
        }

        String normalizedTitle = normalize(value);
        for (int index = 0; index < COUNTRY_TERMS.size(); index++) {
            String normalizedCountry = NORMALIZED_COUNTRY_TERMS.get(index);
            if (normalizedTitle.equals(normalizedCountry + " only")
                    || normalizedTitle.endsWith(" " + normalizedCountry + " only")) {
                return displayCountry(COUNTRY_TERMS.get(index)) + " only";
            }
        }
        return null;
    }

    private String incompatibleTitleLocationCandidate(String candidate) {
        String value = safe(candidate).strip();
        String normalized = normalize(value);
        if (normalized.isBlank() || normalized.equals("remote")) return null;

        if (US_CITY_STATE.matcher(value).matches()) return value;
        for (int index = 0; index < US_STATES.size(); index++) {
            if (normalized.equals(NORMALIZED_US_STATES.get(index))) {
                return US_STATES.get(index) + ", United States";
            }
        }
        if (normalized.matches("(?i)^(?:" + US_STATE_CODES + ")$")) {
            return value.toUpperCase(Locale.ROOT) + ", United States";
        }
        for (int index = 0; index < INCOMPATIBLE_CITIES.size(); index++) {
            if (normalized.equals(NORMALIZED_INCOMPATIBLE_CITIES.get(index))) {
                return INCOMPATIBLE_CITIES.get(index);
            }
        }

        int comma = value.lastIndexOf(',');
        if (comma > 0 && comma < value.length() - 1) {
            String country = value.substring(comma + 1).strip();
            String incompatibleCountry = exactIncompatibleCountry(country);
            if (incompatibleCountry != null) return value.substring(0, comma).strip()
                    + ", " + incompatibleCountry;
        }

        String countryOnly = normalized.endsWith(" only")
                ? normalized.substring(0, normalized.length() - " only".length()).strip()
                : normalized;
        String incompatibleCountry = exactIncompatibleCountry(countryOnly);
        return incompatibleCountry == null ? null
                : incompatibleCountry + (normalized.endsWith(" only") ? " only" : "");
    }

    private String exactIncompatibleCountry(String value) {
        String normalized = normalize(value);
        for (int index = 0; index < COUNTRY_TERMS.size(); index++) {
            if (normalized.equals(NORMALIZED_COUNTRY_TERMS.get(index))) {
                return displayCountry(COUNTRY_TERMS.get(index));
            }
        }
        return null;
    }

    private String countryRestriction(RawJob raw, LocationEvaluationContext context) {
        for (Pattern pattern : List.of(COUNTRY_ONLY, REMOTE_WITHIN_COUNTRY, COUNTRY_RESIDENCE)) {
            Matcher matcher = pattern.matcher(context.restrictionText());
            if (matcher.find()) return displayCountry(matcher.group(1));
        }
        // The restriction text is normalized once per evaluation; each of the seven
        // templates per country is a pre-built normalized needle, so this loop performs
        // no normalization and compiles no expression.
        LocationTextNormalizer.NormalizedText restriction = context.normalizedRestriction();
        for (CountryPhrases phrases : COUNTRY_PHRASES) {
            // Skip all seven templates unless every token of the country name occurs;
            // each template embeds the country, so this cannot hide a match.
            if (!restriction.containsAllTokens(phrases.countryTokens())) continue;
            for (String phrase : phrases.normalizedPhrases()) {
                if (restriction.containsPhrase(phrase)) return displayCountry(phrases.country());
            }
        }
        List<String> structuredRestrictions = new ArrayList<>();
        structuredRestrictions.addAll(raw.locationData().applicantLocationRequirements());
        structuredRestrictions.addAll(raw.locationData().remoteRegions());
        addNonBlank(structuredRestrictions, raw.location());
        for (String value : structuredRestrictions) {
            LocationTextNormalizer.NormalizedText normalized = normalized(value);
            if (normalized.containsPhrase("romania") || normalized.containsPhrase("europe")
                    || normalized.containsPhrase("eu") || normalized.containsPhrase("eea")
                    || normalized.containsPhrase("emea")) {
                continue;
            }
            for (String country : COUNTRY_TERMS) {
                if (normalized.value().equals(country)
                        || normalized.value().equals("remote " + country)) {
                    return displayCountry(country);
                }
            }
        }
        return null;
    }

    private String regionRestriction(LocationEvaluationContext context) {
        String text = context.normalizedRestriction().value();
        if (containsAny(text, "apac only", "asia pacific only", "remote within apac",
                "must be based in apac")) return "APAC";
        if (containsAny(text, "americas only", "north america only", "latin america only",
                "remote within the americas", "must be based in the americas")) return "Americas";
        return null;
    }

    private List<String> restrictions(RawJob raw, boolean officeAttendance,
                                      LocationEvaluationContext context) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        values.addAll(raw.locationData().applicantLocationRequirements());
        if (officeAttendance) values.add("Office attendance required");
        String country = countryRestriction(raw, context);
        if (country != null) values.add(country);
        String region = regionRestriction(context);
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
        LocationTextNormalizer.NormalizedText normalizedValue = normalized(value);
        String text = normalizedValue.value();
        if (text.isBlank()) return WorkplaceType.UNKNOWN;
        if (containsAny(text, "hybrid", "remote until", "partly remote", "partially remote")) {
            return WorkplaceType.HYBRID;
        }
        if (containsAny(text, "telecommute", "fully remote", "100 remote", "work from anywhere",
                "worldwide remote", "remote europe", "remote eu", "remote emea", "romania remote")
                || normalizedValue.containsPhrase("remote")
                || containsAny(text, "home based", "work from home")) {
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

    private boolean containsBucharest(LocationTextNormalizer.NormalizedText text) {
        return text.containsPhrase("bucharest") || text.containsPhrase("bucuresti")
                || text.contains("bucharest metropolitan area");
    }

    private boolean explicitBucharestDescription(String text) {
        return containsAny(text, "located in bucharest", "based in bucharest", "office in bucharest",
                "bucharest based", "located in bucuresti", "based in bucuresti", "office in bucuresti");
    }

    private String normalizedCity(LocationTextNormalizer.NormalizedText locations) {
        if (containsBucharest(locations)) return settings.targetCity();
        if (locations.containsPhrase("ilfov")) return "Ilfov";
        for (int index = 0; index < CITY_LABELS.size(); index++) {
            if (locations.contains(NORMALIZED_CITY_LABELS.get(index))) {
                return CITY_LABELS.get(index);
            }
        }
        return null;
    }

    private String normalizedCountry(LocationTextNormalizer.NormalizedText locations) {
        if (locations.containsPhrase("romania")) return settings.targetCountry();
        for (int index = 0; index < COUNTRY_TERMS.size(); index++) {
            if (locations.containsPhrase(NORMALIZED_COUNTRY_TERMS.get(index))) {
                return displayCountry(COUNTRY_TERMS.get(index));
            }
        }
        return null;
    }

    private String detectedTimezone(String description) {
        Matcher matcher = TIMEZONE_VALUE.matcher(description);
        return matcher.find() ? matcher.group() : null;
    }

    private String detectedWorkAuthorization(String description) {
        Matcher matcher = WORK_AUTHORIZATION.matcher(description);
        return matcher.find() ? matcher.group().strip() : null;
    }

    private boolean incompatibleTimezone(String timezone) {
        return timezone != null && TIMEZONE_VALUE.matcher(timezone).find();
    }

    private String authorizationCountry(String authorization) {
        LocationTextNormalizer.NormalizedText text = normalized(authorization);
        if (text.isBlank() || text.containsPhrase("romania") || text.containsPhrase("europe")
                || text.containsPhrase("eu") || text.containsPhrase("emea")) return null;
        for (int index = 0; index < COUNTRY_TERMS.size(); index++) {
            if (text.containsPhrase(NORMALIZED_COUNTRY_TERMS.get(index))) {
                return displayCountry(COUNTRY_TERMS.get(index));
            }
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

    private boolean standaloneAnywhere(LocationTextNormalizer.NormalizedText normalizedText) {
        String text = normalizedText.value();
        return normalizedText.containsPhrase("anywhere") && !containsAny(text,
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

    private static Set<String> nonRomaniaCountries() {
        Set<String> countries = new HashSet<>();
        for (String code : Locale.getISOCountries()) {
            String name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH)
                    .toLowerCase(Locale.ROOT);
            if (!"romania".equals(name)) countries.add(name);
        }
        countries.addAll(Set.of("usa", "us", "u.s.", "u.s.a.", "uk", "u.k."));
        return Set.copyOf(countries);
    }

    private String normalize(String value) {
        return normalizer.normalize(value);
    }

    /** Normalizes once and keeps the padded form and tokens for repeated phrase checks. */
    private LocationTextNormalizer.NormalizedText normalized(String value) {
        return LocationTextNormalizer.NormalizedText.of(normalizer.normalize(value));
    }

    /** Wraps text that is already normalized; performs no further normalization. */
    private LocationTextNormalizer.NormalizedText alreadyNormalized(String normalizedValue) {
        return LocationTextNormalizer.NormalizedText.of(normalizedValue);
    }

    private boolean word(String text, String value) {
        return normalized(text).containsPhrase(normalizer.normalize(value));
    }

    /** Phrase check against pre-normalized text and a pre-normalized needle. */
    private boolean wordIn(LocationTextNormalizer.NormalizedText text, String normalizedNeedle) {
        return text.containsPhrase(normalizedNeedle);
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
