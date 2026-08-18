package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V15__candidate_profile_scoped_uniqueness extends BaseJavaMigration {
    private static final String TABLE = "candidate_profiles";
    private static final String CANDIDATE_ID = "candidate_id";
    private static final String PROFILE_VERSION = "profile_version";
    private static final String ACTIVE_SLOT = "active_slot";
    private static final String PROFILE_VERSION_CONSTRAINT =
            "candidate_profiles_candidate_profile_version_uk";
    private static final String ACTIVE_SLOT_CONSTRAINT =
            "candidate_profiles_candidate_active_slot_uk";
    private static final String MIGRATION_DEFINITION = String.join("\n",
            "table=resolved-schema." + TABLE,
            "discover=single-column-unique:" + PROFILE_VERSION,
            "discover=single-column-unique:" + ACTIVE_SLOT,
            "add=" + PROFILE_VERSION_CONSTRAINT + ":" + CANDIDATE_ID + "," + PROFILE_VERSION,
            "add=" + ACTIVE_SLOT_CONSTRAINT + ":" + CANDIDATE_ID + "," + ACTIVE_SLOT,
            "drop=discovered-global:" + PROFILE_VERSION,
            "drop=discovered-global:" + ACTIVE_SLOT);

    /** Java migrations have no checksum by default, so hash the canonical schema definition. */
    @Override
    public Integer getChecksum() {
        CRC32 checksum = new CRC32();
        checksum.update(MIGRATION_DEFINITION.getBytes(StandardCharsets.UTF_8));
        return (int) checksum.getValue();
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        String schema = connection.getSchema();
        if (schema == null || schema.isBlank()) {
            throw new IllegalStateException("Candidate profile migration requires a current schema");
        }
        Map<String, List<String>> uniqueConstraints = uniqueConstraints(connection, schema);
        String profileVersionConstraint = singleColumnConstraint(
                uniqueConstraints, PROFILE_VERSION);
        String activeSlotConstraint = singleColumnConstraint(uniqueConstraints, ACTIVE_SLOT);
        if (profileVersionConstraint.equals(activeSlotConstraint)) {
            throw new IllegalStateException(
                    "Candidate profile global UNIQUE constraints must have distinct names");
        }

        String table = quote(connection, schema) + "." + quote(connection, TABLE);
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD CONSTRAINT "
                    + quote(connection, PROFILE_VERSION_CONSTRAINT)
                    + " UNIQUE (" + quote(connection, CANDIDATE_ID) + ", "
                    + quote(connection, PROFILE_VERSION) + ")");
            statement.execute("ALTER TABLE " + table + " ADD CONSTRAINT "
                    + quote(connection, ACTIVE_SLOT_CONSTRAINT)
                    + " UNIQUE (" + quote(connection, CANDIDATE_ID) + ", "
                    + quote(connection, ACTIVE_SLOT) + ")");
            statement.execute("ALTER TABLE " + table + " DROP CONSTRAINT "
                    + quote(connection, profileVersionConstraint));
            statement.execute("ALTER TABLE " + table + " DROP CONSTRAINT "
                    + quote(connection, activeSlotConstraint));
        }
    }

    private Map<String, List<String>> uniqueConstraints(Connection connection, String schema)
            throws SQLException {
        String sql = """
                SELECT tc.constraint_name, kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON kcu.constraint_catalog = tc.constraint_catalog
                 AND kcu.constraint_schema = tc.constraint_schema
                 AND kcu.constraint_name = tc.constraint_name
                 AND kcu.table_catalog = tc.table_catalog
                 AND kcu.table_schema = tc.table_schema
                 AND kcu.table_name = tc.table_name
                WHERE tc.table_schema = ?
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'UNIQUE'
                ORDER BY tc.constraint_name, kcu.ordinal_position
                """;
        Map<String, List<String>> constraints = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, TABLE);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                            .add(rows.getString(2));
                }
            }
        }
        return constraints;
    }

    private String singleColumnConstraint(Map<String, List<String>> constraints, String column) {
        List<String> matches = constraints.entrySet().stream()
                .filter(entry -> entry.getValue().equals(List.of(column)))
                .map(Map.Entry::getKey)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("Expected exactly one single-column UNIQUE constraint on "
                    + TABLE + "(" + column + "), found " + matches.size() + ": " + matches);
        }
        return matches.getFirst();
    }

    private String quote(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        if (quote == null || quote.isBlank()) {
            throw new IllegalStateException("Database does not provide an identifier quote string");
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }
}
