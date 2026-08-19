package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 4 model-label quarantine.
 *
 * <p>The original calibration flag is copied into the new canonical learning
 * gate so upgrades preserve already-vetted samples. Settlement evidence is
 * then backfilled by bet id where a learning sample exists; unknown legacy
 * evidence remains excluded instead of being guessed into training truth.
 */
public class V20260807001__phase_04_learning_eligibility extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        add(connection, metaData, "paper_trade_learning_sample", "learning_eligible",
                "BOOLEAN DEFAULT FALSE NOT NULL");
        add(connection, metaData, "paper_trade_learning_sample", "learning_exclusion_reason",
                "VARCHAR(64) NULL");
        add(connection, metaData, "settlement_evidence", "learning_eligible",
                "BOOLEAN DEFAULT FALSE NOT NULL");
        add(connection, metaData, "settlement_evidence", "learning_exclusion_reason",
                "VARCHAR(64) NULL");

        backfillLearningSamples(connection, metaData);
        backfillSettlementEvidence(connection, metaData);

        ensureIndex(connection, metaData, "paper_trade_learning_sample",
                "idx_paper_learning_eligible_event",
                columnExists(metaData, "paper_trade_learning_sample", "event_occurred_at")
                        ? "learning_eligible, event_occurred_at"
                        : "learning_eligible");
        ensureIndex(connection, metaData, "settlement_evidence",
                "idx_settlement_evidence_learning", "learning_eligible, bundle_as_of");
    }

    private void backfillLearningSamples(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!columnsExist(metaData, "paper_trade_learning_sample",
                "learning_eligible", "learning_exclusion_reason", "calibration_eligible", "status")) {
            return;
        }
        execute(connection, """
                UPDATE paper_trade_learning_sample
                SET learning_eligible = calibration_eligible,
                    learning_exclusion_reason = CASE
                        WHEN calibration_eligible = TRUE THEN NULL
                        WHEN UPPER(status) NOT IN ('WON', 'LOST') THEN 'NON_BINARY_OUTCOME'
                        ELSE 'LEGACY_LOW_CONFIDENCE'
                    END
                """);
        quarantineLegacyArchiveLabels(connection, metaData);
    }

    private void quarantineLegacyArchiveLabels(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!columnsExist(metaData, "paper_trade_learning_sample",
                "learning_eligible", "learning_exclusion_reason", "settlement_source", "settlement_reason")) {
            return;
        }
        execute(connection, """
                UPDATE paper_trade_learning_sample
                SET learning_eligible = FALSE,
                    learning_exclusion_reason = 'LEGACY_ARCHIVE_UNVERIFIED'
                WHERE learning_eligible = TRUE
                  AND (
                    UPPER(COALESCE(settlement_source, '')) LIKE '%OFFICIAL%'
                    OR UPPER(COALESCE(settlement_source, '')) LIKE '%DATABASE%'
                    OR UPPER(COALESCE(settlement_source, '')) LIKE '%ARCHIVE%'
                    OR UPPER(COALESCE(settlement_reason, '')) LIKE '%OFFICIAL%'
                    OR UPPER(COALESCE(settlement_reason, '')) LIKE '%DATABASE%'
                    OR UPPER(COALESCE(settlement_reason, '')) LIKE '%ARCHIVE%'
                  )
                """);
    }

    private void backfillSettlementEvidence(Connection connection, DatabaseMetaData metaData) throws SQLException {
        if (!columnsExist(metaData, "settlement_evidence",
                "bet_id", "learning_eligible", "learning_exclusion_reason")
                || !columnsExist(metaData, "paper_trade_learning_sample",
                "bet_id", "learning_eligible", "learning_exclusion_reason")) {
            return;
        }

        execute(connection, """
                UPDATE settlement_evidence
                SET learning_eligible = FALSE,
                    learning_exclusion_reason = 'LEGACY_EVIDENCE_UNCLASSIFIED'
                """);

        Map<Long, Eligibility> byBet = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT bet_id, learning_eligible, learning_exclusion_reason
                     FROM paper_trade_learning_sample
                     """)) {
            while (rows.next()) {
                byBet.put(rows.getLong("bet_id"), new Eligibility(
                        rows.getBoolean("learning_eligible"),
                        rows.getString("learning_exclusion_reason")
                ));
            }
        }
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE settlement_evidence
                SET learning_eligible = ?, learning_exclusion_reason = ?
                WHERE bet_id = ?
                """)) {
            for (Map.Entry<Long, Eligibility> entry : byBet.entrySet()) {
                update.setBoolean(1, entry.getValue().eligible());
                update.setString(2, entry.getValue().reason());
                update.setLong(3, entry.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void add(Connection connection,
                     DatabaseMetaData metaData,
                     String table,
                     String column,
                     String definition) throws SQLException {
        if (!tableExists(metaData, table) || columnExists(metaData, table, column)) {
            return;
        }
        execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void ensureIndex(Connection connection,
                             DatabaseMetaData metaData,
                             String table,
                             String index,
                             String columns) throws SQLException {
        if (!tableExists(metaData, table) || indexExists(metaData, table, index)) {
            return;
        }
        execute(connection, "CREATE INDEX " + index + " ON " + table + " (" + columns + ")");
    }

    private boolean columnsExist(DatabaseMetaData metaData, String table, String... columns) throws SQLException {
        if (!tableExists(metaData, table)) {
            return false;
        }
        for (String column : columns) {
            if (!columnExists(metaData, table, column)) {
                return false;
            }
        }
        return true;
    }

    private boolean tableExists(DatabaseMetaData metaData, String table) throws SQLException {
        try (ResultSet rows = metaData.getTables(null, null, null, new String[]{"TABLE"})) {
            while (rows.next()) {
                if (same(rows.getString("TABLE_NAME"), table)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnExists(DatabaseMetaData metaData, String table, String column) throws SQLException {
        try (ResultSet rows = metaData.getColumns(null, null, null, null)) {
            while (rows.next()) {
                if (same(rows.getString("TABLE_NAME"), table)
                        && same(rows.getString("COLUMN_NAME"), column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean indexExists(DatabaseMetaData metaData, String table, String index) throws SQLException {
        try (ResultSet rows = metaData.getIndexInfo(
                null, null, table.toUpperCase(Locale.ROOT), false, false)) {
            while (rows.next()) {
                if (same(rows.getString("TABLE_NAME"), table)
                        && same(rows.getString("INDEX_NAME"), index)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean same(String actual, String expected) {
        return actual != null && expected != null && actual.equalsIgnoreCase(expected);
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Eligibility(boolean eligible, String reason) {
    }
}
