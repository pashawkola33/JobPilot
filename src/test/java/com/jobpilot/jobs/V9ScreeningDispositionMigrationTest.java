package com.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V9ScreeningDispositionMigrationTest {
    @Test
    void backfillsLegacyScreeningStateAndEnforcesDispositionValues() throws Exception {
        String url = "jdbc:h2:mem:v9migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "").target("8").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate(insert("match", "BUCHAREST_LOCAL", "ELIGIBLE"));
            statement.executeUpdate(insert("review", "REMOTE_ELIGIBILITY_UNKNOWN", "UNKNOWN"));
        }

        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT external_id, screening_disposition, relevance_disposition, screening_reasons "
                            + "FROM jobs ORDER BY external_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("screening_disposition")).isEqualTo("MATCH");
                assertThat(rows.getString("relevance_disposition")).isEqualTo("MATCH");
                var mapper = new ObjectMapper();
                var reasons = mapper.readTree(rows.getString("screening_reasons"));
                // H2's PostgreSQL mode exposes a cast JSONB literal as a textual JSON node;
                // PostgreSQL stores the same migration default directly as a JSONB array.
                if (reasons.isTextual()) reasons = mapper.readTree(reasons.textValue());
                assertThat(reasons).isEqualTo(mapper.createArrayNode());
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("screening_disposition")).isEqualTo("REVIEW");
            }
            try (var column = statement.executeQuery(
                    "SELECT data_type FROM information_schema.columns "
                            + "WHERE table_name='jobs' AND column_name='screening_reasons'")) {
                assertThat(column.next()).isTrue();
                assertThat(column.getString("data_type")).isEqualToIgnoringCase("json");
            }
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE jobs SET screening_disposition='MAYBE' WHERE external_id='match'"))
                    .isInstanceOf(java.sql.SQLException.class);
            statement.executeUpdate(insertWithoutScreening("new-default"));
            try (var inserted = statement.executeQuery(
                    "SELECT screening_disposition, location_disposition, career_disposition, "
                            + "relevance_disposition FROM jobs WHERE external_id='new-default'")) {
                assertThat(inserted.next()).isTrue();
                assertThat(inserted.getString("screening_disposition")).isEqualTo("REVIEW");
                assertThat(inserted.getString("location_disposition")).isEqualTo("REVIEW");
                assertThat(inserted.getString("career_disposition")).isEqualTo("REVIEW");
                assertThat(inserted.getString("relevance_disposition")).isEqualTo("REVIEW");
            }
        }
    }

    @Test
    void finalDispositionBackfillExplicitlyCombinesAllThreeStages() throws Exception {
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream(
                "/db/migration/V9__screening_dispositions.sql"))) {
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            String finalBackfill = sql.substring(sql.indexOf(
                    "UPDATE jobs SET screening_disposition"));

            assertThat(finalBackfill)
                    .contains("location_disposition = 'REJECT'",
                            "career_disposition = 'REJECT'",
                            "relevance_disposition = 'REJECT'",
                            "location_disposition = 'REVIEW'",
                            "career_disposition = 'REVIEW'",
                            "relevance_disposition = 'REVIEW'");
        }
    }

    private String insert(String id, String location, String career) {
        return """
                INSERT INTO jobs(source, provider_tenant, external_id, canonical_url, title, company,
                    description, fetched_at, first_seen_at, last_seen_at, status, raw_payload_hash,
                    description_hash, normalized_fingerprint, location_eligibility,
                    early_career_eligibility)
                VALUES ('greenhouse', 'tenant', '%s', 'https://example.com/%s', 'Intern', 'Company',
                    'Description', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NEW',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
                    '%s', '%s')
                """.formatted(id, id, location, career);
    }

    private String insertWithoutScreening(String id) {
        return """
                INSERT INTO jobs(source, provider_tenant, external_id, canonical_url, title, company,
                    description, fetched_at, first_seen_at, last_seen_at, status, raw_payload_hash,
                    description_hash, normalized_fingerprint)
                VALUES ('greenhouse', 'tenant', '%s', 'https://example.com/%s', 'Intern', 'Company',
                    'Description', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NEW',
                    'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                    'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                    'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff')
                """.formatted(id, id);
    }
}
