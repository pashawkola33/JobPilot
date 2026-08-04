package com.jobpilot.sources.workday;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Finds the tenant-specific name of the country facet without hardcoding it.
 *
 * <p>Workday uses global reference GUIDs for countries, so the Romania identifier is the
 * same on every tenant, but the facet <em>parameter name</em> is tenant configuration:
 * {@code Country} and {@code Location_Country} appear at the top level, while
 * {@code locationCountry} is nested one level inside {@code locationMainGroup}. Rather
 * than maintain a per-tenant map, this locates whichever facet actually contains the
 * Romania GUID and returns its parameter name.
 */
@Component
public class WorkdayFacetResolver {
    /** Workday's global reference id for Romania; identical across tenants. */
    public static final String ROMANIA_COUNTRY_ID = "f2e609fe92974a55a05fc1cdc2852122";

    private static final int MAX_DEPTH = 2;

    public Optional<String> resolveCountryFacet(JsonNode facets) {
        return resolveCountryFacet(facets, ROMANIA_COUNTRY_ID);
    }

    /** Returns the facetParameter whose own values contain the wanted country id. */
    public Optional<String> resolveCountryFacet(JsonNode facets, String countryId) {
        if (facets == null || !facets.isArray() || countryId == null) return Optional.empty();
        for (JsonNode facet : facets) {
            Optional<String> found = search(facet, countryId, 0);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private Optional<String> search(JsonNode facet, String countryId, int depth) {
        if (facet == null || !facet.isObject() || depth > MAX_DEPTH) return Optional.empty();
        JsonNode values = facet.get("values");
        if (values == null || !values.isArray()) return Optional.empty();

        String parameter = text(facet, "facetParameter");
        for (JsonNode value : values) {
            if (!value.isObject()) continue;
            // A direct hit means this facet is the country facet.
            if (countryId.equals(text(value, "id")) && parameter != null && !parameter.isBlank()) {
                return Optional.of(parameter);
            }
        }
        // Grouping facets such as locationMainGroup carry the real facets inside their values.
        for (JsonNode value : values) {
            Optional<String> nested = search(value, countryId, depth + 1);
            if (nested.isPresent()) return nested;
        }
        return Optional.empty();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }
}
