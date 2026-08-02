package com.jobpilot.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V8ProviderTenantMigrationTest {
    @Test
    void migratesLegacyRowsAndReplacesTheOldUniqueIdentity() throws Exception {
        String url = "jdbc:h2:mem:v8migration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "").target("7").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO jobs(source, external_id, canonical_url, title, company, description,
                        fetched_at, first_seen_at, last_seen_at, status, raw_payload_hash,
                        description_hash, normalized_fingerprint)
                    VALUES ('greenhouse', '42', 'https://legacy.example/42', 'Intern', 'Legacy',
                        'Legacy description', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        'NEW', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                        'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc')
                    """);
        }

        Flyway.configure().dataSource(url, "sa", "").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT provider_tenant FROM jobs WHERE canonical_url='https://legacy.example/42'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("legacy");
            }
            statement.executeUpdate(copyInsert("tenant-a", "https://a.example/42"));
            statement.executeUpdate(copyInsert("tenant-b", "https://b.example/42"));
            assertThatThrownBy(() -> statement.executeUpdate(
                    copyInsert("tenant-a", "https://duplicate.example/42")))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    private String copyInsert(String tenant, String url) {
        return """
                INSERT INTO jobs(source, provider_tenant, external_id, canonical_url, title, company,
                    description, fetched_at, first_seen_at, last_seen_at, status, raw_payload_hash,
                    description_hash, normalized_fingerprint)
                VALUES ('greenhouse', '%s', '42', '%s', 'Intern', 'Company', 'Description',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'NEW',
                    'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc')
                """.formatted(tenant, url);
    }
}
