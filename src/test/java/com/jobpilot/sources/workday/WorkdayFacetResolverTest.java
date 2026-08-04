package com.jobpilot.sources.workday;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class WorkdayFacetResolverTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkdayFacetResolver resolver = new WorkdayFacetResolver();

    private JsonNode facets(String fixture) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/workday/" + fixture)) {
            return mapper.readTree(in).get("facets");
        }
    }

    @Test
    void resolvesTheFlatCountryFacetShape() throws Exception {
        assertThat(resolver.resolveCountryFacet(facets("db-search-bootstrap.json")))
                .contains("Country");
    }

    @Test
    void resolvesTheFlatLocationCountryFacetShape() throws Exception {
        assertThat(resolver.resolveCountryFacet(facets("nxp-search-bootstrap.json")))
                .contains("Location_Country");
    }

    @Test
    void resolvesTheNestedLocationCountryFacetShape() throws Exception {
        assertThat(resolver.resolveCountryFacet(facets("lseg-search-bootstrap.json")))
                .contains("locationCountry");
    }

    @Test
    void returnsEmptyWhenNoFacetCarriesTheRomaniaIdentifier() throws Exception {
        assertThat(resolver.resolveCountryFacet(facets("search-no-romania-facet.json"))).isEmpty();
    }

    @Test
    void returnsEmptyForAbsentOrMalformedFacets() throws Exception {
        assertThat(resolver.resolveCountryFacet(null)).isEmpty();
        assertThat(resolver.resolveCountryFacet(mapper.readTree("{}"))).isEmpty();
        assertThat(resolver.resolveCountryFacet(mapper.readTree("[]"))).isEmpty();
        assertThat(resolver.resolveCountryFacet(mapper.readTree("[{\"values\":[]}]"))).isEmpty();
        assertThat(resolver.resolveCountryFacet(mapper.readTree("[1,2,3]"))).isEmpty();
    }

    @Test
    void ignoresAMatchingValueOnAFacetWithoutAParameterName() throws Exception {
        JsonNode facets = mapper.readTree("""
                [{"descriptor": "Country",
                  "values": [{"descriptor": "Romania",
                              "id": "f2e609fe92974a55a05fc1cdc2852122"}]}]""");

        assertThat(resolver.resolveCountryFacet(facets)).isEmpty();
    }

    @Test
    void usesTheGlobalRomaniaIdentifierSharedAcrossTenants() {
        assertThat(WorkdayFacetResolver.ROMANIA_COUNTRY_ID)
                .isEqualTo("f2e609fe92974a55a05fc1cdc2852122");
    }
}
