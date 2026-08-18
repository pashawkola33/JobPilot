package com.jobpilot.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * V14 on H2 in PostgreSQL mode, like the V8, V9, V12 and V13 migration tests. No container, no
 * network, no live database.
 *
 * <p>Both paths matter: an existing installation already holds candidate_profiles rows that must
 * come out owned, and a fresh database migrates through V14 with the table still empty.
 */
class V14CandidateOwnershipMigrationTest {
    @Test
    void backfillsExistingProfilesToTheSeededDefaultCandidate() throws Exception {
        String url = "jdbc:h2:mem:v14backfill;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "").target("13").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(profile(1, false));
            statement.executeUpdate(profile(2, true));
        }

        Flyway.configure().dataSource(url, "sa", "").target("14").load().migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT id, stable_key FROM candidates")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("stable_key")).isEqualTo("default");
                assertThat(rows.next()).as("exactly one seeded candidate").isFalse();
            }
            // Superseded versions belong to the same candidate as the active one: profile
            // history is not reassigned or orphaned by gaining an owner.
            try (var rows = statement.executeQuery("""
                    SELECT p.profile_version, p.active, c.stable_key
                    FROM candidate_profiles p JOIN candidates c ON c.id = p.candidate_id
                    ORDER BY p.profile_version
                    """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("profile_version")).isEqualTo(1);
                assertThat(rows.getBoolean("active")).isFalse();
                assertThat(rows.getString("stable_key")).isEqualTo("default");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("profile_version")).isEqualTo(2);
                assertThat(rows.getBoolean("active")).isTrue();
                assertThat(rows.getString("stable_key")).isEqualTo("default");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void appliesToAFreshDatabaseWhereNoProfileExistsYet() throws Exception {
        String url = migrate("v14fresh");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) c FROM flyway_schema_history "
                            + "WHERE version = '14' AND success = TRUE")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isEqualTo(1);
            }
            // Bootstrap runs at application start, long after Flyway, so the NOT NULL below is
            // reached with an empty table on every fresh install.
            try (var rows = statement.executeQuery("SELECT COUNT(*) c FROM candidate_profiles")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isZero();
            }
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) c FROM candidates WHERE stable_key = 'default'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isEqualTo(1);
            }
        }
    }

    @Test
    void requiresAnExistingOwnerForEveryProfile() throws Exception {
        String url = migrate("v14ownership");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(profile(1, true)))
                    .as("no candidate_id").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(ownedProfile(1, true, 987_654L)))
                    .as("unknown candidate_id").isInstanceOf(SQLException.class);

            statement.executeUpdate(ownedProfile(1, true, defaultCandidateId(statement)));
        }
    }

    @Test
    void keepsCandidateStableKeysUniqueAndNonBlank() throws Exception {
        String url = migrate("v14candidatekey");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(candidate("default")))
                    .as("duplicate stable key").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(candidate("   ")))
                    .as("blank stable key").isInstanceOf(SQLException.class);

            statement.executeUpdate(candidate("second-candidate"));
        }
    }

    /**
     * The limitation this PR deliberately leaves in place: ownership exists, but a second
     * candidate still cannot hold an active profile or reuse a version number, because V2's
     * global UNIQUE constraints are untouched. A dedicated follow-up makes both candidate-scoped.
     */
    @Test
    void profileVersionAndActiveSlotRemainGloballyUnique() throws Exception {
        String url = migrate("v14globaluniqueness");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            long owner = defaultCandidateId(statement);
            statement.executeUpdate(candidate("second-candidate"));
            long other = candidateId(statement, "second-candidate");
            statement.executeUpdate(ownedProfile(1, true, owner));

            assertThatThrownBy(() -> statement.executeUpdate(ownedProfile(1, false, other)))
                    .as("version 1 for another candidate").isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(ownedProfile(2, true, other)))
                    .as("second active profile").isInstanceOf(SQLException.class);

            statement.executeUpdate(ownedProfile(2, false, other));
        }
    }

    private String migrate(String name) {
        String url = "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
        Flyway.configure().dataSource(url, "sa", "").target("14").load().migrate();
        return url;
    }

    private long defaultCandidateId(Statement statement) throws Exception {
        return candidateId(statement, "default");
    }

    private long candidateId(Statement statement, String stableKey) throws Exception {
        try (var rows = statement.executeQuery(
                "SELECT id FROM candidates WHERE stable_key = '" + stableKey + "'")) {
            assertThat(rows.next()).isTrue();
            return rows.getLong("id");
        }
    }

    private String candidate(String stableKey) {
        return "INSERT INTO candidates (stable_key, created_at, updated_at) VALUES ('"
                + stableKey + "', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    }

    private String profile(int version, boolean active) {
        return profileColumns("", version, active, "");
    }

    private String ownedProfile(int version, boolean active, long candidateId) {
        return profileColumns("candidate_id, ", version, active, candidateId + ", ");
    }

    private String profileColumns(String ownerColumn, int version, boolean active, String ownerValue) {
        return """
                INSERT INTO candidate_profiles (%sprofile_version, full_name, location,
                    education_institution, degree, study_start_year, current_student,
                    final_year_student, commercial_java_experience_years, source_hash,
                    created_at, updated_at, active, active_slot)
                VALUES (%s%d, 'Stored Candidate', 'Bucharest, Romania', 'Stored University',
                    'BSc', 2025, TRUE, FALSE, 0, 'hash-%d', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                    %s, %s)
                """.formatted(ownerColumn, ownerValue, version, version,
                active ? "TRUE" : "FALSE", active ? "1" : "NULL");
    }
}
