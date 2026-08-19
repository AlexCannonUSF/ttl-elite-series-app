package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Pins immutable artifact/schema/calibration/policy identity to runs and calls. */
public class V20260815002__model_call_artifact_identity extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData metadata = connection.getMetaData();
        add(connection, metadata, "paper_trade_model_call", "artifact_checksum", "VARCHAR(64)");
        add(connection, metadata, "paper_trade_model_call", "feature_schema_checksum", "VARCHAR(64)");
        add(connection, metadata, "paper_trade_model_call", "calibration_id", "VARCHAR(100)");
        add(connection, metadata, "paper_trade_model_call", "policy_id", "VARCHAR(100)");
        add(connection, metadata, "paper_trade_model_call", "code_revision", "VARCHAR(80)");
        add(connection, metadata, "paper_trade_model_call", "raw_model_probability", "DOUBLE");
        add(connection, metadata, "paper_trade_model_call", "confidence_low", "DOUBLE");
        add(connection, metadata, "paper_trade_model_call", "confidence_high", "DOUBLE");
        add(connection, metadata, "paper_trade_model_call", "model_market_no_vig_gap", "DOUBLE");
        add(connection, metadata, "paper_trade_model_call", "gate_results", "VARCHAR(1200)");
        add(connection, metadata, "paper_trade_decision_sample", "gate_results", "VARCHAR(1200)");
        add(connection, metadata, "paper_trade_session", "effective_artifact_checksum", "VARCHAR(64)");
        add(connection, metadata, "paper_trade_session", "feature_schema_checksum", "VARCHAR(64)");
        add(connection, metadata, "paper_trade_session", "calibration_id", "VARCHAR(100)");
        add(connection, metadata, "paper_trade_session", "frozen_run_summary", "VARCHAR(4000)");
        add(connection, metadata, "paper_trade_session", "frozen_run_summary_checksum", "VARCHAR(64)");
        requireTelemetry(connection, "selection_score");
        requireTelemetry(connection, "signal_quality");
    }

    private static void requireTelemetry(Connection connection, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        if (!tableExists(metadata, "paper_trade_model_call")
                || !columnExists(metadata, "paper_trade_model_call", column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE paper_trade_model_call SET " + column + " = 0 WHERE " + column + " IS NULL");
            statement.execute("ALTER TABLE paper_trade_model_call ALTER COLUMN " + column + " SET NOT NULL");
        }
    }

    private static void add(Connection connection,
                            DatabaseMetaData metadata,
                            String table,
                            String column,
                            String type) throws SQLException {
        if (tableExists(metadata, table) && !columnExists(metadata, table, column)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + table + " ADD " + column + " " + type);
            }
        }
    }

    private static boolean tableExists(DatabaseMetaData metadata, String table) throws SQLException {
        for (String candidate : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = metadata.getTables(null, null, candidate, new String[]{"TABLE"})) {
                while (rows.next()) {
                    if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean columnExists(DatabaseMetaData metadata, String table, String column) throws SQLException {
        for (String candidate : new String[]{table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = metadata.getColumns(null, null, candidate, null)) {
                while (rows.next()) {
                    if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
                            && column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
