package com.jobpilot.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import db.migration.V15__candidate_profile_scoped_uniqueness;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class V15CandidateProfileScopedUniquenessMigrationTest {
    private static final String VERSION_CONSTRAINT =
            "candidate_profiles_candidate_profile_version_uk";
    private static final String ACTIVE_CONSTRAINT =
            "candidate_profiles_candidate_active_slot_uk";

    @Test
    void upgradesV14DataAndScopesVersionAndActiveUniquenessByCandidate() throws Exception {
        String url = url("v15upgrade");
        migrate(url, "14");
        Map<Integer, StoredProfile> before;
        long owner;
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            owner = candidateId(statement, "default");
            statement.executeUpdate(ownedProfile(1, false, owner));
            statement.executeUpdate(ownedProfile(2, true, owner));
            before = profiles(statement);
        }

        migrate(url, "15");
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertThat(profiles(statement)).isEqualTo(before);

            statement.executeUpdate(candidate("second-candidate"));
            long other = candidateId(statement, "second-candidate");
            statement.executeUpdate(ownedProfile(1, true, other));

            assertThatThrownBy(() -> statement.executeUpdate(ownedProfile(1, false, other)))
                    .as("duplicate profile version for one candidate")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(ownedProfile(2, true, other)))
                    .as("second active profile for one candidate")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(profile(3, false)))
                    .as("missing candidate_id")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(ownedProfile(3, false, 987_654L)))
                    .as("unknown candidate_id")
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                    "DELETE FROM candidates WHERE id = " + other))
                    .as("candidate owning profiles remains protected by ON DELETE RESTRICT")
                    .isInstanceOf(SQLException.class);

            Map<String, List<String>> constraints = uniqueConstraints(statement);
            assertThat(constraints)
                    .containsEntry(VERSION_CONSTRAINT, List.of("candidate_id", "profile_version"))
                    .containsEntry(ACTIVE_CONSTRAINT, List.of("candidate_id", "active_slot"));
            assertThat(constraints.values())
                    .noneMatch(columns -> columns.equals(List.of("profile_version")))
                    .noneMatch(columns -> columns.equals(List.of("active_slot")));
        }
    }

    @Test
    void migratesAFreshDatabaseThroughV15() throws Exception {
        String url = url("v15fresh");
        migrate(url, "15");

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                    "SELECT COUNT(*) c, MAX(checksum) checksum FROM flyway_schema_history "
                            + "WHERE version = '15' AND success = TRUE")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isOne();
                assertThat(rows.getInt("checksum")).isEqualTo(
                        new V15__candidate_profile_scoped_uniqueness().getChecksum());
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) c FROM candidate_profiles")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("c")).isZero();
            }
            assertThat(uniqueConstraints(statement))
                    .containsEntry(VERSION_CONSTRAINT, List.of("candidate_id", "profile_version"))
                    .containsEntry(ACTIVE_CONSTRAINT, List.of("candidate_id", "active_slot"));
        }
    }

    private void migrate(String url, String target) {
        Flyway.configure().dataSource(url, "sa", "").target(target).load().migrate();
    }

    private String url(String name) {
        return "jdbc:h2:mem:" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";
    }

    private Map<Integer, StoredProfile> profiles(Statement statement) throws Exception {
        Map<Integer, StoredProfile> profiles = new LinkedHashMap<>();
        try (var rows = statement.executeQuery("""
                SELECT id, candidate_id, profile_version, active, active_slot
                FROM candidate_profiles ORDER BY profile_version
                """)) {
            while (rows.next()) {
                short activeSlotValue = rows.getShort("active_slot");
                Short activeSlot = rows.wasNull() ? null : activeSlotValue;
                profiles.put(rows.getInt("profile_version"), new StoredProfile(
                        rows.getLong("id"), rows.getLong("candidate_id"),
                        rows.getInt("profile_version"), rows.getBoolean("active"),
                        activeSlot));
            }
        }
        return profiles;
    }

    private Map<String, List<String>> uniqueConstraints(Statement statement) throws Exception {
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        try (var rows = statement.executeQuery("""
                SELECT tc.constraint_name, kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_catalog = tc.constraint_catalog
                 AND kcu.constraint_schema = tc.constraint_schema
                 AND kcu.constraint_name = tc.constraint_name
                 AND kcu.table_catalog = tc.table_catalog
                 AND kcu.table_schema = tc.table_schema
                 AND kcu.table_name = tc.table_name
                WHERE tc.table_name = 'candidate_profiles'
                  AND tc.constraint_type = 'UNIQUE'
                ORDER BY tc.constraint_name, kcu.ordinal_position
                """)) {
            while (rows.next()) {
                constraints.computeIfAbsent(rows.getString("constraint_name"),
                                ignored -> new java.util.ArrayList<>())
                        .add(rows.getString("column_name"));
            }
        }
        return constraints;
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

    private String profileColumns(String ownerColumn, int version, boolean active,
                                  String ownerValue) {
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

    private record StoredProfile(long id, long candidateId, int profileVersion,
                                 boolean active, Short activeSlot) {
    }
}
