package com.jobpilot.jobs.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Expected location-screening output, captured from the implementation as it stood
 * BEFORE the Phase 3.2.6 performance refactor.
 *
 * <p>This file is the refactor's safety net: it is generated once from the original
 * behaviour and must never be regenerated to make a failing test pass. A diff here means
 * screening semantics changed, which Phase 3.2.6 explicitly forbids.
 */
final class ExpectedLocationSnapshots {
    static final Map<String, String> ALL = build();

    private ExpectedLocationSnapshots() {
    }

    private static Map<String, String> build() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("bucharest-onsite",
                "disposition=MATCH\neligibility=BUCHAREST_LOCAL\nworkplace=ONSITE\nscope=UNKNOWN\ncity=Bucharest\ncountry=Romania\neligibleFromRomania=true\nreason=Explicit Bucharest location\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Explicit Bucharest location");
        map.put("bucharest-diacritics",
                "disposition=MATCH\neligibility=BUCHAREST_LOCAL\nworkplace=ONSITE\nscope=UNKNOWN\ncity=Bucharest\ncountry=Romania\neligibleFromRomania=true\nreason=Explicit Bucharest location\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Explicit Bucharest location");
        map.put("bucharest-mixed-case",
                "disposition=MATCH\neligibility=BUCHAREST_LOCAL\nworkplace=ONSITE\nscope=UNKNOWN\ncity=Bucharest\ncountry=Romania\neligibleFromRomania=true\nreason=Explicit Bucharest location\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Explicit Bucharest location");
        map.put("bucharest-hybrid",
                "disposition=MATCH\neligibility=BUCHAREST_LOCAL\nworkplace=HYBRID\nscope=UNKNOWN\ncity=Bucharest\ncountry=Romania\neligibleFromRomania=true\nreason=Explicit Bucharest location\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Explicit Bucharest location");
        map.put("bucharest-remote",
                "disposition=MATCH\neligibility=BUCHAREST_LOCAL\nworkplace=REMOTE\nscope=ROMANIA\ncity=Bucharest\ncountry=Romania\neligibleFromRomania=true\nreason=Explicit Bucharest location\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Explicit Bucharest location");
        map.put("romania-remote",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=ROMANIA\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to Romania\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to Romania");
        map.put("eu-remote",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EU\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to EU\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to EU");
        map.put("eea-remote",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EEA\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to EEA\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to EEA");
        map.put("emea-remote",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EMEA\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to EMEA\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to EMEA");
        map.put("remote-europe",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EUROPE\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to Europe\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to Europe");
        map.put("europe-timezone",
                "disposition=REVIEW\neligibility=REMOTE_ELIGIBILITY_UNKNOWN\nworkplace=REMOTE\nscope=UNKNOWN\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role found, but permitted countries were not specified\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_UNCERTAIN/Remote role found, but permitted countries were not specified");
        map.put("worldwide-remote",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=WORLDWIDE\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to worldwide\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to worldwide");
        map.put("us-only-remote",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=COUNTRY_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role restricted to United States residents\nrestrictions=[United States]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Remote role restricted to United States residents");
        map.put("canada-only-remote",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=COUNTRY_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role restricted to Canada residents\nrestrictions=[Canada]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Remote role restricted to Canada residents");
        map.put("uk-only-remote",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=COUNTRY_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role restricted to United Kingdom residents\nrestrictions=[United Kingdom]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Remote role restricted to United Kingdom residents");
        map.put("germany-only-remote",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=COUNTRY_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role restricted to Germany residents\nrestrictions=[Germany]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Remote role restricted to Germany residents");
        map.put("apac-only-remote",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=REGION_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role restricted to APAC\nrestrictions=[APAC]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Remote role restricted to APAC");
        map.put("country-allowlist",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=COUNTRY_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role restricted to Poland residents\nrestrictions=[Romania, Poland, Germany]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Remote role restricted to Poland residents");
        map.put("country-exclusion",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EUROPE\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to Europe\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to Europe");
        map.put("timezone-restriction",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=REGION_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Required timezone is incompatible with Romania\nrestrictions=[Timezone: PST]\ntimezone=PST\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Required timezone is incompatible with Romania");
        map.put("work-authorization",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=COUNTRY_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Work authorization required in United States\nrestrictions=[Work authorization: United States]\ntimezone=null\nauthorization=United States work authorization required\nreasons=LOCATION/LOCATION_REJECTED/Work authorization required in United States");
        map.put("onsite-cluj",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=ONSITE\nscope=UNKNOWN\ncity=Cluj-Napoca\ncountry=null\neligibleFromRomania=false\nreason=Onsite role located in Cluj-Napoca\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Onsite role located in Cluj-Napoca");
        map.put("onsite-london",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=ONSITE\nscope=COUNTRY_RESTRICTED\ncity=London\ncountry=null\neligibleFromRomania=false\nreason=Explicit structured location is incompatible with Romania: London\nrestrictions=[Structured location: London]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Explicit structured location is incompatible with Romania: London");
        map.put("hybrid-berlin",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=HYBRID\nscope=COUNTRY_RESTRICTED\ncity=Berlin\ncountry=null\neligibleFromRomania=false\nreason=Explicit structured location is incompatible with Romania: Berlin\nrestrictions=[Structured location: Berlin]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Explicit structured location is incompatible with Romania: Berlin");
        map.put("onsite-new-york",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=ONSITE\nscope=COUNTRY_RESTRICTED\ncity=New York\ncountry=null\neligibleFromRomania=false\nreason=Explicit structured location is incompatible with Romania: New York\nrestrictions=[Structured location: New York]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Explicit structured location is incompatible with Romania: New York");
        map.put("office-attendance",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=REMOTE\nscope=REGION_RESTRICTED\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role requires office attendance outside Bucharest\nrestrictions=[Office attendance required]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Remote role requires office attendance outside Bucharest");
        map.put("ambiguous-remote",
                "disposition=REVIEW\neligibility=REMOTE_ELIGIBILITY_UNKNOWN\nworkplace=REMOTE\nscope=UNKNOWN\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote role found, but permitted countries were not specified\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_UNCERTAIN/Remote role found, but permitted countries were not specified");
        map.put("remote-noise",
                "disposition=REVIEW\neligibility=REMOTE_ELIGIBILITY_UNKNOWN\nworkplace=UNKNOWN\nscope=UNKNOWN\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote employment was not established\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_UNCERTAIN/Remote employment was not established");
        map.put("temporary-remote",
                "disposition=REVIEW\neligibility=REMOTE_ELIGIBILITY_UNKNOWN\nworkplace=REMOTE\nscope=UNKNOWN\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Remote arrangement is temporary\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_UNCERTAIN/Remote arrangement is temporary");
        map.put("structured-contradiction",
                "disposition=REVIEW\neligibility=REMOTE_ELIGIBILITY_UNKNOWN\nworkplace=REMOTE\nscope=UNKNOWN\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Provider workplace data contradicts the description\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_UNCERTAIN/Provider workplace data contradicts the description");
        map.put("description-contradiction",
                "disposition=REVIEW\neligibility=REMOTE_ELIGIBILITY_UNKNOWN\nworkplace=ONSITE\nscope=UNKNOWN\ncity=Berlin\ncountry=null\neligibleFromRomania=false\nreason=Structured location contradicts the description\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_UNCERTAIN/Structured location contradicts the description");
        map.put("many-country-names",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EUROPE\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to Europe\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to Europe");
        map.put("misleading-substrings",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EUROPE\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to Europe\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to Europe");
        map.put("punctuation-whitespace",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EUROPE\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to Europe\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to Europe");
        map.put("null-location",
                "disposition=MATCH\neligibility=REMOTE_ROMANIA_ELIGIBLE\nworkplace=REMOTE\nscope=EUROPE\ncity=null\ncountry=Romania\neligibleFromRomania=true\nreason=Fully remote role open to Europe\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Fully remote role open to Europe");
        map.put("blank-fields",
                "disposition=REVIEW\neligibility=REMOTE_ELIGIBILITY_UNKNOWN\nworkplace=UNKNOWN\nscope=UNKNOWN\ncity=null\ncountry=null\neligibleFromRomania=false\nreason=Workplace type is missing\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_UNCERTAIN/Workplace type is missing");
        map.put("null-description",
                "disposition=MATCH\neligibility=BUCHAREST_LOCAL\nworkplace=ONSITE\nscope=UNKNOWN\ncity=Bucharest\ncountry=Romania\neligibleFromRomania=true\nreason=Explicit Bucharest location\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_MATCH/Explicit Bucharest location");
        map.put("ilfov",
                "disposition=REJECT\neligibility=REJECTED_LOCATION\nworkplace=ONSITE\nscope=UNKNOWN\ncity=Ilfov\ncountry=Romania\neligibleFromRomania=false\nreason=Onsite role located in Ilfov\nrestrictions=[]\ntimezone=null\nauthorization=null\nreasons=LOCATION/LOCATION_REJECTED/Onsite role located in Ilfov");
        return Map.copyOf(map);
    }
}
