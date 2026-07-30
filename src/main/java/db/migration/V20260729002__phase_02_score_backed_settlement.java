package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Phase 2 score-backed settlement observability.
 *
 * <p>All columns are nullable except the contradiction flag so existing bets
 * remain explicitly "unknown" until they are evaluated by the new score
 * evidence analyzer.
 */
public class V20260729002__phase_02_score_backed_settlement extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metaData = connection.getMetaData();

        add(connection, metaData, "paper_trade_bet", "score_evidence_quality", "VARCHAR(24) NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_finality", "VARCHAR(32) NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_confidence", "DOUBLE NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_observation_count", "INTEGER NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_source_count", "INTEGER NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_agreeing_sources", "INTEGER NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_completion_signals", "INTEGER NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_inferred_winner_id", "BIGINT NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_latest_score", "VARCHAR(64) NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_latest_phase", "VARCHAR(32) NULL");
        add(connection, metaData, "paper_trade_bet", "score_evidence_contradictory",
                "BOOLEAN DEFAULT FALSE NOT NULL");

        add(connection, metaData, "settlement_evidence", "score_evidence_quality", "VARCHAR(24) NULL");
        add(connection, metaData, "settlement_evidence", "score_evidence_finality", "VARCHAR(32) NULL");
        add(connection, metaData, "settlement_evidence", "score_evidence_confidence", "DOUBLE NULL");
        add(connection, metaData, "settlement_evidence", "score_observation_count", "INTEGER NULL");
        add(connection, metaData, "settlement_evidence", "score_source_count", "INTEGER NULL");
        add(connection, metaData, "settlement_evidence", "score_completion_signal_count", "INTEGER NULL");
        add(connection, metaData, "settlement_evidence", "score_inferred_winner_id", "BIGINT NULL");

        ensureIndex(connection, metaData, "paper_trade_bet",
                "idx_paper_bet_score_evidence", "score_evidence_quality, score_evidence_confidence");
        ensureIndex(connection, metaData, "settlement_evidence",
                "idx_settlement_evidence_score_quality", "score_evidence_quality, bundle_as_of");
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

    private boolean tableExists(DatabaseMetaData metaData, String table) throws SQLException {
        try (ResultSet resultSet = metaData.getTables(null, null, null, new String[]{"TABLE"})) {
            while (resultSet.next()) {
                if (same(resultSet.getString("TABLE_NAME"), table)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean columnExists(DatabaseMetaData metaData, String table, String column) throws SQLException {
        try (ResultSet resultSet = metaData.getColumns(null, null, null, null)) {
            while (resultSet.next()) {
                if (same(resultSet.getString("TABLE_NAME"), table)
                        && same(resultSet.getString("COLUMN_NAME"), column)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean indexExists(DatabaseMetaData metaData, String table, String index) throws SQLException {
        try (ResultSet resultSet = metaData.getIndexInfo(
                null,
                null,
                table.toUpperCase(Locale.ROOT),
                false,
                false
        )) {
            while (resultSet.next()) {
                if (same(resultSet.getString("TABLE_NAME"), table)
                        && same(resultSet.getString("INDEX_NAME"), index)) {
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
}
